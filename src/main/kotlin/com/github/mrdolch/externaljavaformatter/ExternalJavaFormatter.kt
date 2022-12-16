package com.github.mrdolch.externaljavaformatter

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.CoreFormattingService
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiFile
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.internal.synchronized
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Duration


class ExternalJavaFormatter : AsyncDocumentFormattingService() {
  override fun getFeatures(): Set<FormattingService.Feature> = setOf()
  override fun runAfter(): Class<CoreFormattingService> = CoreFormattingService::class.java
  override fun getTimeout(): Duration = Duration.ofSeconds(5)
  override fun canFormat(file: PsiFile): Boolean = file.fileType.name == "JAVA"
  private fun getRelevantJdk(project: Project): Sdk? = ProjectRootManager.getInstance(project).projectSdk

  @OptIn(InternalCoroutinesApi::class)
  override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
    val jdk = getRelevantJdk(formattingRequest.context.project) ?: return null
    val file = formattingRequest.ioFile ?: return null

    val configuration = formattingRequest.context.project
      .getService(PersistConfigurationService::class.java).state

    if (configuration.enabled != true) return null

    synchronized(this) {
      return object : FormattingTask {
        private var handler: OSProcessHandler? = null
        private var isCanceled = false

        override fun isRunUnderProgress(): Boolean = true

        override fun cancel(): Boolean {
          if (handler == null) isCanceled = true
          else handler!!.destroyProcess()
          return true
        }

        override fun run() {
          val commandLine = buildCommandLine(configuration, jdk, file)
          if (isCanceled) return;
          val handler = OSProcessHandler(commandLine)
          handler.addProcessListener(object : CapturingProcessAdapter() {
            override fun processTerminated(event: ProcessEvent) =
              when {
                isCanceled -> {}
                event.exitCode == 0 -> formattingRequest.onTextReady(output.stdout)
                else -> formattingRequest.onError("FormattingError", output.stderr)
              }
          })
          handler.startNotify()
        }
      }
    }
  }

  private fun buildCommandLine(
    configuration: PersistConfigurationService.Configuration, jdk: Sdk, file: File
  ): GeneralCommandLine {
    val params = SimpleJavaParameters()
    params.jdk = jdk
    params.workingDirectory = configuration.workingDir
    params.mainClass = configuration.mainClass
    configuration.classPath?.run {
      trim().split("[:;]+".toRegex()).forEach(params.classPath::add)
    }
    configuration.arguments?.run {
      trim().split("\\s+".toRegex()).forEach { argument ->
        if (argument != "{}") params.programParametersList.add(argument)
        else params.programParametersList.add(file.absolutePath)
      }
    }
    configuration.vmOptions?.run {
      trim().split("\\n+".toRegex()).forEach(params.vmParametersList::add)
    }
    return params.toCommandLine().withCharset(StandardCharsets.UTF_8)
  }

  override fun getNotificationGroupId(): String {
    return "external google-java-formatter";
  }

  override fun getName(): String {
    return "external google-java-formatter";
  }
}