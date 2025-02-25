/**
 * Copyright 2016 Bryan Kelly
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.btkelly.gnag.tasks;

import static com.btkelly.gnag.models.GitHubStatusType.FAILURE;
import static com.btkelly.gnag.utils.ReportWriter.REPORT_FILE_NAME;

import com.btkelly.gnag.GnagPlugin;
import com.btkelly.gnag.extensions.GnagPluginExtension;
import com.btkelly.gnag.models.CheckStatus;
import com.btkelly.gnag.models.Violation;
import com.btkelly.gnag.reporters.AndroidLintViolationDetector;
import com.btkelly.gnag.reporters.BaseExecutedViolationDetector;
import com.btkelly.gnag.reporters.CheckstyleViolationDetector;
import com.btkelly.gnag.reporters.DetektViolationDetector;
import com.btkelly.gnag.reporters.FindbugsViolationDetector;
import com.btkelly.gnag.reporters.KtlintViolationDetector;
import com.btkelly.gnag.reporters.PMDViolationDetector;
import com.btkelly.gnag.reporters.ViolationDetector;
import com.btkelly.gnag.utils.ProjectHelper;
import com.btkelly.gnag.utils.ReportWriter;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.logging.Logger;
import org.gradle.api.logging.Logging;
import org.gradle.api.tasks.StopExecutionException;
import org.gradle.api.tasks.TaskAction;

/**
 * Created by bobbake4 on 4/1/16.
 */
public class GnagCheckTask extends DefaultTask {

  static final String TASK_NAME = "gnagCheck";
  private final List<ViolationDetector> violationDetectors = new ArrayList<>();
  private final ProjectHelper projectHelper = new ProjectHelper(getProject());
  private GnagPluginExtension gnagPluginExtension;

  public static void addTask(ProjectHelper projectHelper, GnagPluginExtension gnagPluginExtension) {
    Map<String, Object> taskOptions = new HashMap<>();

    taskOptions.put(Task.TASK_NAME, TASK_NAME);
    taskOptions.put(Task.TASK_TYPE, GnagCheckTask.class);
    taskOptions.put(Task.TASK_GROUP, "Verification");
    taskOptions.put(Task.TASK_DEPENDS_ON, "check");
    taskOptions.put(Task.TASK_DESCRIPTION, "Runs Gnag checks and generates an HTML report");

    Project project = projectHelper.getProject();

    GnagCheckTask gnagCheckTask = (GnagCheckTask) project.task(taskOptions, TASK_NAME);
    gnagCheckTask.setGnagPluginExtension(gnagPluginExtension);

    if (gnagPluginExtension.checkstyle.isEnabled() && projectHelper.hasJavaSourceFiles()) {
      gnagCheckTask.violationDetectors
          .add(new CheckstyleViolationDetector(project, gnagPluginExtension.checkstyle));
    }

    if (gnagPluginExtension.pmd.isEnabled() && projectHelper.hasJavaSourceFiles()) {
      gnagCheckTask.violationDetectors
          .add(new PMDViolationDetector(project, gnagPluginExtension.pmd));
    }

    if (gnagPluginExtension.findbugs.isEnabled() && projectHelper.hasJavaSourceFiles()) {
      gnagCheckTask.violationDetectors
          .add(new FindbugsViolationDetector(project, gnagPluginExtension.findbugs));
    }

    if (gnagPluginExtension.ktlint.isEnabled() && projectHelper.hasKotlinSourceFiles()) {
      String overrideToolVersion = gnagPluginExtension.ktlint.getToolVersion();
      String toolVersion = overrideToolVersion != null ? overrideToolVersion : "0.35.0";

      project.getConfigurations().create("gnagKtlint");
      project.getDependencies().add("gnagKtlint", "com.pinterest:ktlint:" + toolVersion);

      Task ktlintTask = KtlintTask.addTask(projectHelper);
      gnagCheckTask.dependsOn(ktlintTask);
      gnagCheckTask.violationDetectors
          .add(new KtlintViolationDetector(project, gnagPluginExtension.ktlint));
    }

    if (gnagPluginExtension.detekt.isEnabled() && projectHelper.hasKotlinSourceFiles()) {
      String overrideToolVersion = gnagPluginExtension.detekt.getToolVersion();
      String toolVersion = overrideToolVersion != null ? overrideToolVersion : "1.0.1";

      project.getConfigurations().create("gnagDetekt");
      project.getDependencies().add("gnagDetekt", "io.gitlab.arturbosch.detekt:detekt-cli:" + toolVersion);

      Task detektTask = DetektTask
          .addTask(projectHelper, gnagPluginExtension.detekt.getReporterConfig());
      gnagCheckTask.dependsOn(detektTask);
      gnagCheckTask.violationDetectors
          .add(new DetektViolationDetector(project, gnagPluginExtension.detekt));
    }

    if (projectHelper.isAndroidProject() && gnagPluginExtension.androidLint.isEnabled()) {
      gnagCheckTask.violationDetectors
          .add(new AndroidLintViolationDetector(project, gnagPluginExtension.androidLint));
    }
  }

  @SuppressWarnings("unused")
  @TaskAction
  public void taskAction() {
    if (gnagPluginExtension.isEnabled()) {
      executeGnagCheck();
    }
  }

  @Override
  public Logger getLogger() {
    return Logging.getLogger(GnagPlugin.class);
  }

  private void executeGnagCheck() {
    final Set<Violation> allDetectedViolations = new HashSet<>();

    violationDetectors.forEach(violationDetector -> {
      if (violationDetector instanceof BaseExecutedViolationDetector) {
        ((BaseExecutedViolationDetector) violationDetector).executeReporter();
      }

      final List<Violation> detectedViolations = violationDetector.getDetectedViolations();
      allDetectedViolations.addAll(detectedViolations);

      getLogger().lifecycle(
          violationDetector.name() + " detected " + detectedViolations.size() + " violations.");
    });

    final File reportsDir = projectHelper.getReportsDir();

    if (allDetectedViolations.isEmpty()) {
      ReportWriter.deleteLocalReportFiles(reportsDir, getLogger());

      getProject().setStatus(CheckStatus.getSuccessfulCheckStatus());

      getLogger().lifecycle("Congrats, no poop code found!");
    } else {
      ReportWriter.writeLocalReportFiles(allDetectedViolations, reportsDir, getLogger());

      getProject().setStatus(new CheckStatus(FAILURE, allDetectedViolations));

      final String failedMessage
          = "One or more violation detectors has found violations. Check the report at "
          + reportsDir
          + File.separatorChar
          + REPORT_FILE_NAME + " for details.";

      if (gnagPluginExtension.shouldFailOnError() && !taskExecutionGraphIncludesGnagReport()) {
        throw new GradleException(failedMessage);
      } else {
        getLogger().lifecycle(failedMessage);
        throw new StopExecutionException(failedMessage);
      }
    }
  }

  private void setGnagPluginExtension(GnagPluginExtension gnagPluginExtension) {
    this.gnagPluginExtension = gnagPluginExtension;
  }

  private boolean taskExecutionGraphIncludesGnagReport() {
    for (final Task task : getProject().getGradle().getTaskGraph().getAllTasks()) {
      if (task.getName().equals(GnagReportTask.TASK_NAME)) {
        return true;
      }
    }

    return false;
  }

}
