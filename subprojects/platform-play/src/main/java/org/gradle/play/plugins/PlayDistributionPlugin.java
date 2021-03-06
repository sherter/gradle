/*
 * Copyright 2014 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.play.plugins;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import org.apache.commons.lang.StringUtils;
import org.gradle.api.Action;
import org.gradle.api.Incubating;
import org.gradle.api.InvalidUserCodeException;
import org.gradle.api.Task;
import org.gradle.api.artifacts.ResolvedArtifact;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.component.ProjectComponentIdentifier;
import org.gradle.api.file.CopySpec;
import org.gradle.api.file.FileCopyDetails;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.file.copy.CopySpecInternal;
import org.gradle.api.tasks.Sync;
import org.gradle.api.tasks.application.CreateStartScripts;
import org.gradle.api.tasks.bundling.AbstractArchiveTask;
import org.gradle.api.tasks.bundling.Tar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.internal.reflect.Instantiator;
import org.gradle.internal.service.ServiceRegistry;
import org.gradle.jvm.tasks.Jar;
import org.gradle.model.*;
import org.gradle.play.PlayApplicationBinarySpec;
import org.gradle.play.distribution.PlayDistribution;
import org.gradle.play.distribution.PlayDistributionContainer;
import org.gradle.play.internal.PlayApplicationBinarySpecInternal;
import org.gradle.play.internal.distribution.DefaultPlayDistribution;
import org.gradle.play.internal.distribution.DefaultPlayDistributionContainer;
import org.gradle.util.GUtil;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import static org.gradle.internal.FileUtils.hasExtension;

/**
 * A plugin that adds a distribution zip to a Play application build.
 */
@SuppressWarnings("UnusedDeclaration")
@Incubating
public class PlayDistributionPlugin extends RuleSource {
    public static final String DISTRIBUTION_GROUP = "distribution";
    public static final String DIST_LIFECYCLE_TASK_NAME = "dist";
    public static final String STAGE_LIFECYCLE_TASK_NAME = "stage";

    @Model
    PlayDistributionContainer distributions(ServiceRegistry serviceRegistry) {
        Instantiator instantiator = serviceRegistry.get(Instantiator.class);
        return new DefaultPlayDistributionContainer(instantiator);
    }

    @Mutate
    void createLifecycleTasks(ModelMap<Task> tasks) {
        tasks.create(DIST_LIFECYCLE_TASK_NAME, new Action<Task>() {
            @Override
            public void execute(Task task) {
                task.setDescription("Assembles all Play distributions.");
                task.setGroup(DISTRIBUTION_GROUP);
            }
        });

        tasks.create(STAGE_LIFECYCLE_TASK_NAME, new Action<Task>() {
            @Override
            public void execute(Task task) {
                task.setDescription("Stages all Play distributions.");
                task.setGroup(DISTRIBUTION_GROUP);
            }
        });
    }

    @Defaults
    void createDistributions(@Path("distributions") PlayDistributionContainer distributions, ModelMap<PlayApplicationBinarySpecInternal> playBinaries, PlayPluginConfigurations configurations, ServiceRegistry serviceRegistry) {
        FileOperations fileOperations = serviceRegistry.get(FileOperations.class);
        Instantiator instantiator = serviceRegistry.get(Instantiator.class);
        for (PlayApplicationBinarySpecInternal binary : playBinaries) {
            PlayDistribution distribution = instantiator.newInstance(DefaultPlayDistribution.class, binary.getProjectScopedName(), fileOperations.copySpec(), binary);
            distribution.setBaseName(binary.getProjectScopedName());
            distributions.add(distribution);
        }
    }

    @Mutate
    void createDistributionContentTasks(ModelMap<Task> tasks, final @Path("buildDir") File buildDir,
                                        final @Path("distributions") PlayDistributionContainer distributions,
                                        final PlayPluginConfigurations configurations) {
        for (final PlayDistribution distribution : distributions.withType(PlayDistribution.class)) {
            final PlayApplicationBinarySpec binary = distribution.getBinary();
            if (binary == null) {
                throw new InvalidUserCodeException(String.format("Play Distribution '%s' does not have a configured Play binary.", distribution.getName()));
            }

            final File distJarDir = new File(buildDir, String.format("distributionJars/%s", distribution.getName()));
            final String jarTaskName = String.format("create%sDistributionJar", StringUtils.capitalize(distribution.getName()));
            tasks.create(jarTaskName, Jar.class, new Action<Jar>() {
                @Override
                public void execute(Jar jar) {
                    jar.setDescription("Assembles an application jar suitable for deployment for the " + binary + ".");
                    jar.dependsOn(binary.getTasks().withType(Jar.class));
                    jar.from(jar.getProject().zipTree(binary.getJarFile()));
                    jar.setDestinationDir(distJarDir);
                    jar.setArchiveName(binary.getJarFile().getName());

                    Map<String, Object> classpath = Maps.newHashMap();
                    classpath.put("Class-Path", new PlayManifestClasspath(configurations.getPlayRun(), binary.getAssetsJarFile()));
                    jar.getManifest().attributes(classpath);
                }
            });
            final Task distributionJar = tasks.get(jarTaskName);

            final File scriptsDir = new File(buildDir, String.format("scripts/%s", distribution.getName()));
            String createStartScriptsTaskName = String.format("create%sStartScripts", StringUtils.capitalize(distribution.getName()));
            tasks.create(createStartScriptsTaskName, CreateStartScripts.class, new Action<CreateStartScripts>() {
                @Override
                public void execute(CreateStartScripts createStartScripts) {
                    createStartScripts.setDescription("Creates OS specific scripts to run the " + binary + ".");
                    createStartScripts.setClasspath(distributionJar.getOutputs().getFiles());
                    createStartScripts.setMainClassName("play.core.server.NettyServer");
                    createStartScripts.setApplicationName(distribution.getName());
                    createStartScripts.setOutputDir(scriptsDir);
                }
            });
            Task createStartScripts = tasks.get(createStartScriptsTaskName);

            CopySpecInternal distSpec = (CopySpecInternal) distribution.getContents();
            CopySpec libSpec = distSpec.addChild().into("lib");
            libSpec.from(distributionJar);
            libSpec.from(binary.getAssetsJarFile());
            libSpec.from(configurations.getPlayRun().getAllArtifacts());
            libSpec.eachFile(new PrefixArtifactFileNames(configurations.getPlayRun()));

            CopySpec binSpec = distSpec.addChild().into("bin");
            binSpec.from(createStartScripts);
            binSpec.setFileMode(0755);

            CopySpec confSpec = distSpec.addChild().into("conf");
            confSpec.from("conf").exclude("routes");
            distSpec.from("README");
        }
    }

    @Mutate
    void createDistributionZipTasks(ModelMap<Task> tasks, final @Path("buildDir") File buildDir, PlayDistributionContainer distributions) {
        for (final PlayDistribution distribution : distributions.withType(PlayDistribution.class)) {
            final String stageTaskName = String.format("stage%sDist", StringUtils.capitalize(distribution.getName()));
            final File stageDir = new File(buildDir, "stage");
            final String baseName = StringUtils.isNotEmpty(distribution.getBaseName()) ? distribution.getBaseName() : distribution.getName();
            tasks.create(stageTaskName, Sync.class, new Action<Sync>() {
                @Override
                public void execute(Sync sync) {
                    sync.setDescription("Copies the '" + distribution.getName() + "' distribution to a staging directory.");
                    sync.setDestinationDir(stageDir);

                    CopySpecInternal baseSpec = sync.getRootSpec().addChild();
                    baseSpec.into(baseName);
                    baseSpec.with(distribution.getContents());
                }
            });
            tasks.named(STAGE_LIFECYCLE_TASK_NAME, new Action<Task>() {
                @Override
                public void execute(Task task) {
                    task.dependsOn(stageTaskName);
                }
            });

            final Task stageTask = tasks.get(stageTaskName);
            final String distributionZipTaskName = String.format("create%sZipDist", StringUtils.capitalize(distribution.getName()));
            tasks.create(distributionZipTaskName, Zip.class, new Action<Zip>() {
                @Override
                public void execute(final Zip zip) {
                    zip.setDescription("Packages the '" + distribution.getName() + "' distribution as a zip file.");
                    zip.setBaseName(baseName);
                    zip.setDestinationDir(new File(buildDir, "distributions"));
                    zip.from(stageTask);
                }
            });

            final String distributionTarTaskName = String.format("create%sTarDist", StringUtils.capitalize(distribution.getName()));
            tasks.create(distributionTarTaskName, Tar.class, new Action<Tar>() {
                @Override
                public void execute(final Tar tar) {
                    tar.setDescription("Packages the '" + distribution.getName() + "' distribution as a tar file.");
                    tar.setBaseName(baseName);
                    tar.setDestinationDir(new File(buildDir, "distributions"));
                    tar.from(stageTask);
                }
            });

            tasks.named(distributionTarTaskName, DistributionArchiveRules.class);
            tasks.named(distributionZipTaskName, DistributionArchiveRules.class);

            tasks.named(DIST_LIFECYCLE_TASK_NAME, new Action<Task>() {
                @Override
                public void execute(Task task) {
                    task.dependsOn(distributionZipTaskName, distributionTarTaskName);
                }
            });
        }
    }

    static class DistributionArchiveRules extends RuleSource {
        @Finalize
        void fixupDistributionArchiveNames(AbstractArchiveTask archiveTask) {
            archiveTask.setArchiveName(String.format("%s.%s", archiveTask.getBaseName(), archiveTask.getExtension()));
        }
    }

    /**
     * Represents a classpath to be defined in a jar manifest
     */
    static class PlayManifestClasspath {
        final PlayPluginConfigurations.PlayConfiguration playConfiguration;
        final File assetsJarFile;

        public PlayManifestClasspath(PlayPluginConfigurations.PlayConfiguration playConfiguration, File assetsJarFile) {
            this.playConfiguration = playConfiguration;
            this.assetsJarFile = assetsJarFile;
        }

        @Override
        public String toString() {
            return Joiner.on(" ").join(
                Iterables.transform(
                    Iterables.concat(
                        playConfiguration.getAllArtifacts(),
                        Collections.singleton(assetsJarFile)
                    ),
                    new PrefixArtifactFileNames(playConfiguration)
                )
            );
        }
    }

    static class PrefixArtifactFileNames implements Action<FileCopyDetails>, Function<File, String> {
        private final PlayPluginConfigurations.PlayConfiguration configuration;
        ImmutableMap<File, String> renames;

        PrefixArtifactFileNames(PlayPluginConfigurations.PlayConfiguration configuration) {
            this.configuration = configuration;
        }

        @Override
        public void execute(FileCopyDetails fileCopyDetails) {
            fileCopyDetails.setName(apply(fileCopyDetails.getFile()));
        }

        @Override
        public String apply(File input) {
            calculateRenames();
            String rename = renames.get(input);
            if (rename!=null) {
                return rename;
            }
            return input.getName();
        }

        private void calculateRenames() {
            if (renames == null) {
                renames = calculate();
            }
        }

        private ImmutableMap<File, String> calculate() {
            ImmutableMap.Builder<File, String> files = ImmutableMap.builder();
            for (ResolvedArtifact artifact : getResolvedArtifacts()) {
                boolean isProject = artifact.getId().getComponentIdentifier() instanceof ProjectComponentIdentifier;
                if (isProject) {
                    // rename project dependencies
                    ProjectComponentIdentifier projectComponentIdentifier = (ProjectComponentIdentifier) artifact.getId().getComponentIdentifier();
                    files.put(artifact.getFile(), renameForProject(projectComponentIdentifier, artifact.getFile()));
                } else {
                    boolean isExternalModule = artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier;
                    if (isExternalModule) {
                        ModuleComponentIdentifier moduleComponentIdentifier = (ModuleComponentIdentifier) artifact.getId().getComponentIdentifier();
                        files.put(artifact.getFile(), renameForModule(moduleComponentIdentifier, artifact.getFile()));
                    } else {
                        // don't rename other types of dependencies
                        files.put(artifact.getFile(), artifact.getFile().getName());
                    }
                }
            }
            return files.build();
        }

        Set<ResolvedArtifact> getResolvedArtifacts() {
            return configuration.getConfiguration().getResolvedConfiguration().getResolvedArtifacts();
        }

        static String renameForProject(ProjectComponentIdentifier id, File file) {
            String fileName = file.getName();
            if (shouldBeRenamed(file)) {
                String projectPath = id.getProjectPath();
                projectPath = projectPathToSafeFileName(projectPath);
                return maybePrefix(projectPath, file);
            }
            return fileName;
        }

        static String renameForModule(ModuleComponentIdentifier id, File file) {
            if (shouldBeRenamed(file)) {
                return maybePrefix(id.getGroup(), file);
            }
            return file.getName();
        }

        private static String maybePrefix(String prefix, File file) {
            if (!GUtil.isTrue(prefix)) {
                return file.getName();
            }
            return String.format("%s-%s", prefix, file.getName());
        }

        private static String projectPathToSafeFileName(String projectPath) {
            if (projectPath.equals(":")) {
                return null;
            }
            return projectPath.replaceAll(":", ".").substring(1);
        }

        private static boolean shouldBeRenamed(File file) {
            return hasExtension(file, ".jar");
        }
    }
}
