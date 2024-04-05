package com.github.mrdolch.externaljavaformatter

import com.github.mrdolch.externaljavaformatter.PersistConfigurationService.Configuration
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.SimpleJavaParameters
import com.intellij.execution.process.CapturingProcessAdapter
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.DocumentMerger
import com.intellij.formatting.service.FormattingNotificationService
import com.intellij.openapi.editor.Document
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.encoding.EncodingManager
import java.io.File
import java.io.FileWriter
import java.nio.charset.StandardCharsets

class FormattingRequestExecutor(private val context: FormattingContext, private val document: Document, private val sdk: Sdk, private val configuration: Configuration) {
  private val initialDocumentModificationStamp: Long = document.modificationStamp
  private val notifications = FormattingNotificationService.getInstance(context.project)

  init {
    if (configuration.useStandardIn != true) fileDocumentManager.saveDocument(document)
  }

  internal fun executeExternalFormatterProcess() {
    val fileToFormat = getDocumentFileOnLocalFileSystem()
    val commandLine = createCommandLine(fileToFormat, sdk, configuration.workingDir, configuration.mainClass, configuration.classPath, configuration.arguments, configuration.vmOptions)
    with(OSProcessHandler(commandLine)) {
      addProcessListener(FormattingDoneListener(this@FormattingRequestExecutor, this, document.text))
      startNotify()
      waitFor(timeoutInSeconds * 1000L).let {
        if (!it) notifications.reportError(notificationGroup, name, timeoutMessage)
      }
    }
  }

  internal class FormattingDoneListener(private val request: FormattingRequestExecutor, private val processHandler: ProcessHandler, private val stdIn: String? = null) : CapturingProcessAdapter() {
    override fun startNotified(event: ProcessEvent) {
      val processInput = processHandler.processInput
      if (stdIn != null && processInput != null) {
        processInput.writer().use { it.write(stdIn) }
      }
    }

    override fun processTerminated(event: ProcessEvent) = when {
      event.exitCode != 0 -> request.notifications.reportError(notificationGroup, "FormattingError", output.stderr)
      application.isWriteAccessAllowed -> request.updateDocument(output.stdout)
      else -> with(request) {
        val updateDocumentFun = { updateDocument(output.stdout) }
        val asWriteActionFun = { application.runWriteAction(ThrowableComputable(updateDocumentFun)) }
        val asUndoActionFun = { commandProcessor.runUndoTransparentAction(asWriteActionFun) }
        application.invokeLater(asUndoActionFun)
      }
    }
  }

  private fun getDocumentFileOnLocalFileSystem(): File = context.virtualFile.let { vFile ->
    if (vFile?.isInLocalFileSystem == true) vFile.fileSystem.getNioPath(vFile)?.apply { return toFile() }
    val ext = if (vFile != null) vFile.extension else context.containingFile.fileType.defaultExtension
    val charset = vFile?.charset ?: EncodingManager.getInstance().defaultCharset
    return FileUtilRt.createTempFile("ij-format-temp", ".$ext", true)
        .also { tempFile -> FileWriter(tempFile, charset).use { writer -> writer.write(document.text) } }
  }

  private fun updateDocument(newText: String) {
    DocumentMerger.EP_NAME.extensionList.filter { document.modificationStamp > initialDocumentModificationStamp }
        .find { merger -> merger.updateDocument(document, newText) }
        ?: let { document.setText(newText) }
    fileDocumentManager.saveDocument(document)
  }

  companion object {

    fun createCommandLine(fileToFormat: File, sdk: Sdk, workingDir: String?, mainClass: String?, classPath: String?, arguments: String?, vmOptions: String?): GeneralCommandLine {
      return SimpleJavaParameters().let { params ->
        params.jdk = sdk
        params.workingDirectory = workingDir
        params.mainClass = mainClass
        classPath?.run {
          trim().split("[:;]+".toRegex()).forEach(params.classPath::add)
        }
        arguments?.run {
          trim().split("\\s+".toRegex()).forEach { argument ->
            if (argument != "{}") params.programParametersList.add(argument)
            else params.programParametersList.add(fileToFormat.absolutePath)
          }
        }
        vmOptions?.run {
          trim().split("\\n+".toRegex()).forEach(params.vmParametersList::add)
        }
        params.toCommandLine().withCharset(StandardCharsets.UTF_8)
      }
    }
  }
}