package com.github.mrdolch.externaljavaformatter

import com.github.mrdolch.externaljavaformatter.AsyncDocumentFormattingService.FormattingRequestState.*
import com.intellij.CodeStyleBundle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AbstractDocumentFormattingService
import com.intellij.formatting.service.DocumentMerger
import com.intellij.formatting.service.FormattingNotificationService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.FileUtilRt
import com.intellij.openapi.vfs.encoding.EncodingManager
import com.intellij.util.ThrowableRunnable
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.nio.charset.Charset
import java.time.Duration
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

abstract class AsyncDocumentFormattingService : AbstractDocumentFormattingService() {
  private val logger = Logger.getInstance(AsyncDocumentFormattingService::class.java)
  private val pendingRequests = Collections.synchronizedList(ArrayList<FormattingRequest>())
  private val timeout = Duration.ofSeconds(30)!!
  private val retryPeriod = 1000 // milliseconds

  @Synchronized
  override fun formatDocument(
    document: Document,
    formattingRanges: List<TextRange>, formattingContext: FormattingContext,
    canChangeWhiteSpaceOnly: Boolean, quickFormat: Boolean
  ) {
    findPendingRequest(document)?.let { formattingRequest ->
      if (!formattingRequest.cancel()) return
    }
    val formattingRequest = FormattingRequest(formattingContext, document)
    createFormattingTask(formattingRequest)?.let { formattingTask ->
      formattingRequest.myTask = formattingTask
      pendingRequests.add(formattingRequest)
      when {
        ApplicationManager.getApplication().isHeadlessEnvironment -> {
          runAsyncFormat(formattingRequest, null)
        }

        else -> {
          FormattingProgressTask(formattingRequest)
            .setCancelText(CodeStyleBundle.message("async.formatting.service.cancel", name))
            .queue()
        }
      }
    }
  }

  private fun findPendingRequest(document: Document): FormattingRequest? {
    synchronized(pendingRequests) {
      return pendingRequests.firstOrNull { request -> request.document === document }
    }
  }

  private fun runAsyncFormat(formattingRequest: FormattingRequest, indicator: ProgressIndicator?) {
    try {
      formattingRequest.runTask(indicator)
    } finally {
      pendingRequests.remove(formattingRequest)
    }
  }

  /**
   * Called before the actual formatting starts.
   *
   * @param formattingRequest The formatting request to create the formatting task for.
   * @return [FormattingTask] if successful and formatting can proceed, `null` otherwise. The latter may be a result, for
   * example, of misconfiguration.
   */
  protected abstract fun createFormattingTask(formattingRequest: FormattingRequest): FormattingTask?
  protected abstract val notificationGroupId: String
  protected abstract val name: String

  private enum class FormattingRequestState { NOT_STARTED, RUNNING, CANCELLING, CANCELLED, COMPLETED, EXPIRED }

  inner class FormattingRequest(
    internal val myContext: FormattingContext,
    internal val document: Document
  ) {
    internal val project = myContext.project
    private val myInitialModificationStamp: Long = document.modificationStamp
    private val myTaskSemaphore = Semaphore(1)

    @Volatile
    internal var myTask: FormattingTask? = null
    private var myResult: String? = null
    private val myStateRef = AtomicReference(NOT_STARTED)

    init {
      FileDocumentManager.getInstance().saveDocument(document)
    }

    val iOFile: File?
      get() {
        val originalFile = myContext.virtualFile
        val ext: String?
        val charset: Charset
        if (originalFile != null) {
          if (originalFile.isInLocalFileSystem) {
            val localPath = originalFile.fileSystem.getNioPath(originalFile)
            if (localPath != null) {
              return localPath.toFile()
            }
          }
          ext = originalFile.extension
          charset = originalFile.charset
        } else {
          ext = myContext.containingFile.fileType.defaultExtension
          charset = EncodingManager.getInstance().defaultCharset
        }
        return try {
          val tempFile = FileUtilRt.createTempFile("ij-format-temp", ".$ext", true)
          FileWriter(tempFile, charset).use { writer -> writer.write(document.text) }
          tempFile
        } catch (e: IOException) {
          logger.warn(e)
          null
        }
      }

    fun cancel(): Boolean {
      myTask?.let { formattingTask ->
        if (myStateRef.compareAndSet(RUNNING, CANCELLING) && formattingTask.cancel()) {
          myStateRef.set(CANCELLED)
          myTaskSemaphore.release()
          return true
        }
      }
      logger.warn("Pending request can't be cancelled")
      return false
    }

    fun runTask(indicator: ProgressIndicator?) {
      val task = myTask ?: return
      if (!myStateRef.compareAndSet(NOT_STARTED, RUNNING)) return

      try {
        myTaskSemaphore.acquire()
        task.run()
        var waitTime: Long = 0
        while (waitTime < timeout.seconds * 1000L) {
          if (myTaskSemaphore.tryAcquire(retryPeriod.toLong(), TimeUnit.MILLISECONDS)) {
            myTaskSemaphore.release()
            break
          }
          indicator?.checkCanceled()
          waitTime += retryPeriod.toLong()
        }
        when {
          myStateRef.compareAndSet(RUNNING, EXPIRED) -> {
            FormattingNotificationService.getInstance(project).reportError(
              notificationGroupId, name,
              CodeStyleBundle.message("async.formatting.service.timeout", name, timeout.seconds)
            )
          }

          myResult == null -> {}

          ApplicationManager.getApplication().isWriteAccessAllowed -> updateDocument(myResult!!)

          else -> {
            ApplicationManager.getApplication().invokeLater {
              CommandProcessor.getInstance().runUndoTransparentAction {
                WriteAction.run(ThrowableRunnable {
                  updateDocument(myResult!!)
                })
              }
            }
          }
        }
      } catch (ie: InterruptedException) {
        logger.warn("Interrupted formatting thread.")
      }
    }

    private fun updateDocument(newText: String) {
      if (document.modificationStamp > myInitialModificationStamp) {
        for (merger in DocumentMerger.EP_NAME.extensionList) {
          if (merger.updateDocument(document, newText)) {
            return
          }
        }
      }
      document.setText(newText)

      FileDocumentManager.getInstance().saveDocument(document)
//      PsiDocumentManager.getInstance(project).commitDocument(document)
    }

    fun onTextReady(updatedText: String) {
      if (myStateRef.compareAndSet(RUNNING, COMPLETED)) {
        myResult = updatedText
        myTaskSemaphore.release()
      }
    }

    fun onError(title: String, message: String) {
      if (myStateRef.compareAndSet(RUNNING, COMPLETED)) {
        myTaskSemaphore.release()
        FormattingNotificationService.getInstance(project)
          .reportError(notificationGroupId, title, message)
      }
    }
  }

  interface FormattingTask : Runnable {
    /**
     * Cancel the current runnable.
     * @return `true` if the runnable has been successfully cancelled, `false` otherwise.
     */
    fun cancel(): Boolean
  }

  inner class FormattingProgressTask(private val formattingRequest: FormattingRequest) : Task.Backgroundable(
    formattingRequest.project, CodeStyleBundle.message("async.formatting.service.running", name), true
  ) {
    override fun run(indicator: ProgressIndicator) {
      indicator.isIndeterminate = false
      indicator.fraction = 0.0
      runAsyncFormat(formattingRequest, indicator)
      indicator.fraction = 1.0
    }

    override fun onCancel() {
      formattingRequest.cancel()
    }
  }

}