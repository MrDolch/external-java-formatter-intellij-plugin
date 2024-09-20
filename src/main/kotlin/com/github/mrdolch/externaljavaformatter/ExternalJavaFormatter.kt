package com.github.mrdolch.externaljavaformatter

import com.github.mrdolch.externaljavaformatter.PersistConfigurationService.Configuration
import com.intellij.CodeStyleBundle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AbstractDocumentFormattingService
import com.intellij.formatting.service.FormattingService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import java.util.*

internal const val notificationGroup: String = "external google-java-formatter"
internal const val name: String = "external google-java-formatter"
internal const val timeoutInSeconds = 10
internal const val timeoutMessage = "$name: timeout of $timeoutInSeconds reached."
internal val application = ApplicationManager.getApplication()
internal val fileDocumentManager = FileDocumentManager.getInstance()
internal val commandProcessor = CommandProcessor.getInstance()

class ExternalJavaFormatter : AbstractDocumentFormattingService() {
  private val pendingRequests = Collections.synchronizedSet(mutableSetOf<Document>())
  private val application = ApplicationManager.getApplication()

  override fun getFeatures(): Set<FormattingService.Feature> = setOf()
  override fun canFormat(file: PsiFile): Boolean =
    "JAVA" == file.fileType.name
        && null != getRelevantJdk(file.project)
        && true == getConfiguration(file.project).enabled

  private fun getRelevantJdk(project: Project): Sdk? = ProjectRootManager.getInstance(project).projectSdk
  private fun getConfiguration(project: Project): Configuration =
    project.getService(PersistConfigurationService::class.java).state

  @Synchronized
  override fun formatDocument(
    document: Document, ranges: List<TextRange>, context: FormattingContext,
    canChangeWhiteSpaceOnly: Boolean, quickFormat: Boolean
  ) {
    // return if formatting already in progress
    if (!pendingRequests.add(document)) return

    // enqueue new async request
    FormattingRequestExecutor(context, document, getRelevantJdk(context.project)!!, getConfiguration(context.project))
      .also {
        if (application.isHeadlessEnvironment) execute(it, document)
        else object : Task.Backgroundable(context.project, name, false) {
          override fun run(ignored: ProgressIndicator) = execute(it, document)
        }.queue()
      }
  }

  private fun execute(request: FormattingRequestExecutor, document: Document) = try {
    request.executeExternalFormatterProcess()
  } finally {
    pendingRequests.remove(document)
  }
}