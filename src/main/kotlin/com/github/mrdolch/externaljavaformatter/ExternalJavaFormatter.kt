package com.github.mrdolch.externaljavaformatter

import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.formatting.service.AsyncDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi.PsiFile
import java.nio.charset.StandardCharsets

class ExternalJavaFormatter : AsyncDocumentFormattingService() {
    override fun getFeatures(): MutableSet<FormattingService.Feature> {
        return mutableSetOf()
    }

    override fun canFormat(file: PsiFile): Boolean = file.fileType.name == "JAVA"

    private fun getRelevantJdk(project: Project): Sdk? = ProjectRootManager.getInstance(project).projectSdk
    override fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask? {
        val configuration = formattingRequest.context.project
            .getService(PersistConfigurationService::class.java).state

        if (configuration.enabled != true) return null

        val params = SimpleJavaParameters()
        params.jdk = getRelevantJdk(formattingRequest.context.project)
        configuration.mainClass?.let { params.classPath.add(it) }
        configuration.arguments?.let {
            it.trim().split("\\s+".toRegex()).forEach { arg ->
                if (arg != "{}") params.programParametersList.addAll(arg)
                else params.programParametersList.add(formattingRequest.ioFile!!.absolutePath)
            }
        }
        configuration.vmOptions?.let {
            it.trim().split("\\n+".toRegex())
                .forEach { vmOption -> params.vmParametersList.addAll(vmOption) }
        }
        val handler = OSProcessHandler(params.toCommandLine().withCharset(StandardCharsets.UTF_8))
        return object : FormattingTask {
            override fun isRunUnderProgress(): Boolean = true

            override fun cancel(): Boolean {
                handler.destroyProcess()
                return true
            }

            override fun run() {
                handler.addProcessListener(object : CapturingProcessAdapter() {
                    override fun processTerminated(event: ProcessEvent) =
                        if (event.exitCode == 0) formattingRequest.onTextReady(output.stdout)
                        else formattingRequest.onError("Ein Fehler ist aufgetreten ^^", output.stderr)
                })
                handler.startNotify()
            }
        }
    }

    override fun getNotificationGroupId(): String {
        return "external google-java-formatter";
    }

    override fun getName(): String {
        return "external google-java-formatter";
    }
}