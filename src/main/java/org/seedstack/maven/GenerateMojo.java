/**
 * Copyright (c) 2013-2016, The SeedStack authors <http://seedstack.org>
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.seedstack.maven;

import com.mitchellbosecke.pebble.PebbleEngine;
import com.mitchellbosecke.pebble.error.PebbleException;
import com.mitchellbosecke.pebble.template.PebbleTemplate;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.archetype.ArchetypeManager;
import org.apache.maven.archetype.catalog.Archetype;
import org.apache.maven.archetype.catalog.ArchetypeCatalog;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.BuildPluginManager;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.seedstack.maven.components.ArtifactResolver;
import org.seedstack.maven.components.Prompter;
import org.seedstack.maven.components.PrompterException;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.twdata.maven.mojoexecutor.MojoExecutor.artifactId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.configuration;
import static org.twdata.maven.mojoexecutor.MojoExecutor.element;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executeMojo;
import static org.twdata.maven.mojoexecutor.MojoExecutor.executionEnvironment;
import static org.twdata.maven.mojoexecutor.MojoExecutor.goal;
import static org.twdata.maven.mojoexecutor.MojoExecutor.groupId;
import static org.twdata.maven.mojoexecutor.MojoExecutor.name;
import static org.twdata.maven.mojoexecutor.MojoExecutor.plugin;
import static org.twdata.maven.mojoexecutor.MojoExecutor.version;

/**
 * Defines the generate goal. This goal generates a SeedStack project from existing archetypes.
 *
 * @author adrien.lauer@mpsa.com
 */
@Mojo(name = "generate", requiresProject = false)
public class GenerateMojo extends AbstractMojo {
    private static final String ARCHETYPE_PLUGIN_GROUP_ID = "org.apache.maven.plugins";
    private static final String ARCHETYPE_PLUGIN_ARTIFACT_ID = "maven-archetype-plugin";
    @Parameter(defaultValue = "${project}", required = true, readonly = true)
    private MavenProject mavenProject;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession mavenSession;

    @Component
    private BuildPluginManager buildPluginManager;

    @Component
    private ArtifactResolver artifactResolver;

    @Component
    private ArchetypeManager archetypeManager;

    @Component
    private Prompter prompter;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        String type = mavenSession.getUserProperties().getProperty("type"),
                version = mavenSession.getUserProperties().getProperty("version"),
                archetypeGroupId = mavenSession.getUserProperties().getProperty("archetypeGroupId"),
                archetypeArtifactId = mavenSession.getUserProperties().getProperty("archetypeArtifactId"),
                archetypeVersion = mavenSession.getUserProperties().getProperty("archetypeVersion");

        boolean allowSnapshots = !mavenSession.getUserProperties().getProperty("allowSnapshots", "false").equals("false");

        if (StringUtils.isBlank(version)) {
            version = "1.0.0-SNAPSHOT";
        }

        if (StringUtils.isBlank(archetypeGroupId)) {
            archetypeGroupId = "org.seedstack";
        }

        if (StringUtils.isBlank(archetypeArtifactId)) {
            if (StringUtils.isBlank(type)) {
                Set<String> possibleTypes = findProjectTypes();

                try {
                    if (possibleTypes.isEmpty()) {
                        type = prompter.prompt("Enter the project type");
                    } else {
                        ArrayList<String> list = new ArrayList<>(possibleTypes);
                        Collections.sort(list);
                        type = prompter.prompt("Enter the project type", list);
                    }
                } catch (PrompterException e) {
                    throw new MojoExecutionException("Project type is required", e);
                }
            }
            archetypeArtifactId = String.format("%s-archetype", type);
        }

        if (StringUtils.isBlank(archetypeVersion)) {
            try {
                archetypeVersion = artifactResolver.getHighestVersion(mavenProject, archetypeGroupId, archetypeArtifactId, allowSnapshots);
            } catch (Exception e) {
                throw new MojoExecutionException("Unable to determine latest version of archetype, please specify it manually through the archetypeVersion property", e);
            }
        }

        mavenSession.getUserProperties().setProperty("version", version);

        String groupId = mavenSession.getUserProperties().getProperty("groupId");
        String artifactId = mavenSession.getUserProperties().getProperty("artifactId");
        try {
            if (StringUtils.isBlank(groupId)) {
                groupId = prompter.prompt("Generated project group id");
            }
            if (StringUtils.isBlank(artifactId)) {
                artifactId = prompter.prompt("Generated project artifact id");
            }
        } catch (PrompterException e) {
            throw new MojoExecutionException("Generated project group id and artifact id are required", e);
        }

        mavenSession.getUserProperties().put("groupId", groupId);
        mavenSession.getUserProperties().put("artifactId", artifactId);

        String pluginVersion;
        try {
            pluginVersion = artifactResolver.getHighestVersion(mavenProject, ARCHETYPE_PLUGIN_GROUP_ID, ARCHETYPE_PLUGIN_ARTIFACT_ID, false);
            getLog().info("Using the latest version of archetype plugin: " + pluginVersion);
        } catch (Exception e) {
            getLog().warn("Unable to determine latest version of archetype plugin, falling back to 3.0.0");
            pluginVersion = "3.0.0";
        }

        executeMojo(plugin(groupId(ARCHETYPE_PLUGIN_GROUP_ID), artifactId(ARCHETYPE_PLUGIN_ARTIFACT_ID), version(pluginVersion)),

                goal("generate"),

                configuration(
                        element(name("interactiveMode"), "true"),
                        element(name("archetypeGroupId"), archetypeGroupId),
                        element(name("archetypeArtifactId"), archetypeArtifactId),
                        element(name("archetypeVersion"), archetypeVersion)
                ),

                executionEnvironment(mavenProject, mavenSession, buildPluginManager));

        File projectDir = new File("." + File.separator + artifactId);
        if (projectDir.exists() && projectDir.canWrite()) {
            PebbleEngine engine = new PebbleEngine.Builder().build();
            HashMap<String, Object> vars = new HashMap<>();
            renderTemplates(projectDir, engine, vars);
        }
    }

    private void renderTemplates(File file, PebbleEngine engine, Map<String, Object> vars) {
        if (!file.exists()) {
            return;
        }

        if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File c : files) {
                    renderTemplates(c, engine, vars);
                }
            }
        }

        String fileName = file.getName();
        if (fileName.contains(".tpl")) {
            getLog().info("Rendering template " + file.getPath());
            try {
                String absolutePath = file.getAbsolutePath();
                PebbleTemplate template = engine.getTemplate(absolutePath);
                StringWriter stringWriter = new StringWriter();

                template.evaluate(stringWriter, vars);
                String renderedContent = stringWriter.toString();
                if (renderedContent.trim().length() > 0) {
                    try (Writer writer = new FileWriter(absolutePath.replace(".tpl", ""))) {
                        writer.write(renderedContent);
                    }
                } else {
                    getLog().info("Rendered content is empty, no output file is being generated");
                }

                if (!file.delete()) {
                    getLog().warn("Unable to delete template after rendering, useless additional files may be present");
                }
            } catch (PebbleException | IOException e) {
                getLog().error("Unable to render template, resulting project might be unusable", e);
            }
        }
    }

    private Set<String> findProjectTypes() {
        Set<String> possibleTypes = new HashSet<>();
        getLog().info("Searching for SeedStack archetypes in catalog");
        ArchetypeCatalog remoteCatalog = archetypeManager.getRemoteCatalog();

        for (Archetype archetype : remoteCatalog.getArchetypes()) {
            if ("org.seedstack".equals(archetype.getGroupId())) {
                String artifactId = archetype.getArtifactId();
                if (artifactId.endsWith("-archetype")) {
                    possibleTypes.add(artifactId.substring(0, artifactId.length() - 10));
                }
            }
        }
        return possibleTypes;
    }
}