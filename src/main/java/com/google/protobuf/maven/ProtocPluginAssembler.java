package com.google.protobuf.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.Os;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.collection.CollectRequest;
import org.sonatype.aether.graph.DependencyNode;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.resolution.DependencyRequest;
import org.sonatype.aether.util.graph.PreorderNodeListGenerator;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Creates an executable {@code protoc} plugin (written in Java) from a {@link ProtocPlugin} specification.
 *
 * @since 0.3.0
 */
public class ProtocPluginAssembler {

    private static final String WINRUN4J_EXECUTABLE_PATH = "winrun4j/WinRun4J.exe";

    private final RepositorySystem repoSystem;

    private final RepositorySystemSession repoSystemSession;

    private final List<RemoteRepository> remoteRepos = new ArrayList<RemoteRepository>();

    private final ProtocPlugin pluginDefinition;

    private final File pluginDirectory;

    private final List<File> resolvedJars = new ArrayList<File>();

    private final File pluginExecutableFile;

    public ProtocPluginAssembler(
            final ProtocPlugin pluginDefinition,
            final RepositorySystem repoSystem,
            final RepositorySystemSession repoSystemSession,
            final List<RemoteRepository> remoteRepos,
            final File pluginDirectory) {
        this.repoSystem = repoSystem;
        this.repoSystemSession = repoSystemSession;
        this.remoteRepos.addAll(remoteRepos);
        this.pluginDefinition = pluginDefinition;
        this.pluginDirectory = pluginDirectory;
        this.pluginExecutableFile = pluginDefinition.getPluginExecutableFile(pluginDirectory);
    }

    /**
     * Resolves the plugin's dependencies to the local Maven repository and builds the plugin executable.
     *
     * @throws MojoExecutionException if plugin executable could not be built.
     */
    public void execute() throws MojoExecutionException {
        pluginDefinition.validate();
        resolvePluginDependencies();

        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
            buildWindowsPlugin();
            copyWinRun4JExecutable();
        } else {
            buildUnixPlugin();
            pluginExecutableFile.setExecutable(true);
        }
    }

    private void buildWindowsPlugin() throws MojoExecutionException {
        createPluginDirectory();

        // Try to locate jvm.dll based on pluginDefinition's javaHome property
        final File javaHome = new File(pluginDefinition.getJavaHome());

        // Try JDK location first...
        File jvmLocation = new File(javaHome, "jre/bin/client/jvm.dll");

        // ... then JRE.
        if (!jvmLocation.isFile()) {
            jvmLocation = new File(javaHome, "bin/client/jvm.dll");
        }
        // If still not found, give up and don't set vm.location
        if (!jvmLocation.isFile()) {
            jvmLocation = null;
        }

        final File winRun4JIniFile = new File(pluginDirectory, pluginDefinition.getPluginName() + ".ini");

        PrintWriter out = null;
        try {
            out = new PrintWriter(new FileWriter(winRun4JIniFile));
            if (jvmLocation != null) {
                out.println("vm.location=" + jvmLocation.getAbsolutePath());
            }
            int index = 1;
            for (final File resolvedJar : resolvedJars) {
                out.println("classpath." + index + "=" + resolvedJar.getAbsolutePath());
                index++;
            }
            out.println("main.class=" + pluginDefinition.getMainClass());

            index = 1;
            for (final String arg : pluginDefinition.getArgs()) {
                out.println("arg." + index + "=" + arg);
                index++;
            }

            index = 1;
            for (final String jvmArg : pluginDefinition.getJvmArgs()) {
                out.println("vmarg." + index + "=" + jvmArg);
                index++;
            }

            out.println("vm.version.min=1.6");

            // keep from logging to stdout (the default)
            out.println("log.level=none");
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Could not write WinRun4J ini file: " + winRun4JIniFile.getAbsolutePath(), e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private void copyWinRun4JExecutable() throws MojoExecutionException {
        final URL url = Thread.currentThread().getContextClassLoader().getResource(WINRUN4J_EXECUTABLE_PATH);
        if (url == null) {
            throw new MojoExecutionException(
                    "Could not locate WinRun4J executable at path: " + WINRUN4J_EXECUTABLE_PATH);
        }
        try {
            FileUtils.copyURLToFile(url, pluginExecutableFile);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Could not copy WinRun4J executable to: " + pluginExecutableFile.getAbsolutePath(), e);
        }
    }

    private void buildUnixPlugin() throws MojoExecutionException {
        createPluginDirectory();

        final File javaLocation = new File(pluginDefinition.getJavaHome(), "bin/java");

        PrintWriter out = null;
        try {
            out = new PrintWriter(new FileWriter(pluginExecutableFile));
            out.println("#!/bin/sh");
            out.println();
            out.print("CP=");
            for (int i = 0; i < resolvedJars.size(); i++) {
                if (i > 0) {
                    out.print(":");
                }
                out.print("\"" + resolvedJars.get(i).getAbsolutePath() + "\"");
            }
            out.println();
            out.print("ARGS=\"");
            for (final String arg : pluginDefinition.getArgs()) {
                out.print(arg + " ");
            }
            out.println("\"");
            out.print("JVMARGS=\"");
            for (final String jvmArg : pluginDefinition.getJvmArgs()) {
                out.print(jvmArg + " ");
            }
            out.println("\"");
            out.println();
            out.println("\"" + javaLocation.getAbsolutePath() + "\" $JVMARGS -cp $CP "
                    + pluginDefinition.getMainClass() + " $ARGS");
            out.println();
        } catch (IOException e) {
            throw new MojoExecutionException("Could not write plugin script file: " + pluginExecutableFile, e);
        } finally {
            if (out != null) {
                out.close();
            }
        }
    }

    private void createPluginDirectory() throws MojoExecutionException {
        pluginDirectory.mkdirs();
        if (!pluginDirectory.isDirectory()) {
            throw new MojoExecutionException("Could not create protoc plugin directory: "
                    + pluginDirectory.getAbsolutePath());
        }
    }

    private void resolvePluginDependencies() throws MojoExecutionException {
        final CollectRequest collectRequest = new CollectRequest();
        collectRequest.setRoot(pluginDefinition.asDependency());
        for (final RemoteRepository remoteRepo : remoteRepos) {
            collectRequest.addRepository(remoteRepo);
        }

        try {
            final DependencyNode node = repoSystem.collectDependencies(repoSystemSession, collectRequest).getRoot();
            final DependencyRequest request = new DependencyRequest(node, null);
            repoSystem.resolveDependencies(repoSystemSession, request);
            final PreorderNodeListGenerator nlg = new PreorderNodeListGenerator();
            node.accept(nlg);

            resolvedJars.addAll(nlg.getFiles());
        } catch (Exception e) {
            throw new MojoExecutionException(e.getMessage(), e);
        }
    }
}