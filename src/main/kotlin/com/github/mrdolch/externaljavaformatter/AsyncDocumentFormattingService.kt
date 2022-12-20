package com.github.mrdolch.externaljavaformatter

import com.intellij.CodeStyleBundle
import com.intellij.formatting.FormattingContext
import com.intellij.formatting.service.AbstractDocumentFormattingService
import com.intellij.formatting.service.AsyncFormattingRequest
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
import com.intellij.psi.PsiDocumentManager
import com.intellij.util.ThrowableRunnable
import com.intellij.util.containers.ContainerUtil
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
  private val myPendingRequests = Collections.synchronizedList(ArrayList<AsyncFormattingRequest>())

  @Synchronized
  override fun formatDocument(
    document: Document,
    formattingRanges: List<TextRange>,
    formattingContext: FormattingContext,
    canChangeWhiteSpaceOnly: Boolean,
    quickFormat: Boolean
  ) {
    val currRequest = findPendingRequest(document)
    if (currRequest != null) {
      if (!(currRequest as FormattingRequestImpl).cancel()) {
        logger.warn("Pending request can't be cancelled")
        return
      }
    }
    val formattingRequest = FormattingRequestImpl(
      formattingContext, document, formattingRanges,
      canChangeWhiteSpaceOnly, quickFormat
    )
    val formattingTask = createFormattingTask(formattingRequest)
    if (formattingTask != null) {
      formattingRequest.setTask(formattingTask)
      myPendingRequests.add(formattingRequest)
      if (ApplicationManager.getApplication().isHeadlessEnvironment) {
        runAsyncFormat(formattingRequest, null)
      } else {
        if (formattingTask.isRunUnderProgress) {
          FormattingProgressTask(formattingRequest).setCancelText(
            CodeStyleBundle.message(
              "async.formatting.service.cancel",
              name
            )
          ).queue()
        } else {
          ApplicationManager.getApplication().executeOnPooledThread { runAsyncFormat(formattingRequest, null) }
        }
      }
    }
  }

  private fun findPendingRequest(document: Document): AsyncFormattingRequest? {
    synchronized(myPendingRequests) {
      return ContainerUtil
        .find(myPendingRequests) { request: AsyncFormattingRequest -> (request as FormattingRequestImpl).document === document }
    }
  }

  private fun runAsyncFormat(formattingRequest: FormattingRequestImpl, indicator: ProgressIndicator?) {
    try {
      formattingRequest.runTask(indicator)
    } finally {
      myPendingRequests.remove(formattingRequest)
    }
  }

  /**
   * Called before the actual formatting starts.
   *
   * @param formattingRequest The formatting request to create the formatting task for.
   * @return [FormattingTask] if successful and formatting can proceed, `null` otherwise. The latter may be a result, for
   * example, of misconfiguration.
   */
  protected abstract fun createFormattingTask(formattingRequest: AsyncFormattingRequest): FormattingTask?
  protected abstract val notificationGroupId: String
  protected abstract val name: String

  private enum class FormattingRequestState {
    NOT_STARTED, RUNNING, CANCELLING, CANCELLED, COMPLETED, EXPIRED
  }

  inner class FormattingRequestImpl constructor(
    private val myContext: FormattingContext,
    val document: Document,
    private val myRanges: List<TextRange>,
    private val myCanChangeWhitespaceOnly: Boolean,
    private val myQuickFormat: Boolean
  ) : AsyncFormattingRequest {
    private val myInitialModificationStamp: Long = document.modificationStamp
    private val myTaskSemaphore = Semaphore(1)

    @Volatile
    private var myTask: FormattingTask? = null
    private var myResult: String? = null
    private val myStateRef = AtomicReference(FormattingRequestState.NOT_STARTED)

    init {
      FileDocumentManager.getInstance().saveDocument(document)
    }

    override fun getIOFile(): File? {
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
        FileWriter(tempFile, charset).use { writer -> writer.write(documentText) }
        tempFile
      } catch (e: IOException) {
        logger.warn(e)
        null
      }
    }

    override fun getDocumentText(): String {
      return document.text
    }

    fun cancel(): Boolean {
      val formattingTask = myTask
      if (formattingTask != null && myStateRef.compareAndSet(
          FormattingRequestState.RUNNING,
          FormattingRequestState.CANCELLING
        )
      ) {
        if (formattingTask.cancel()) {
          myStateRef.set(FormattingRequestState.CANCELLED)
          myTaskSemaphore.release()
          return true
        }
      }
      return false
    }

    override fun getFormattingRanges(): List<TextRange> {
      return myRanges
    }

    override fun canChangeWhitespaceOnly(): Boolean {
      return myCanChangeWhitespaceOnly
    }

    override fun getContext(): FormattingContext {
      return myContext
    }

    fun setTask(formattingTask: FormattingTask) {
      myTask = formattingTask
    }

    fun runTask(indicator: ProgressIndicator?) {
      val task = myTask
      if (task != null && myStateRef.compareAndSet(
          FormattingRequestState.NOT_STARTED,
          FormattingRequestState.RUNNING
        )
      ) {
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
          if (myStateRef.compareAndSet(FormattingRequestState.RUNNING, FormattingRequestState.EXPIRED)) {
            FormattingNotificationService.getInstance(myContext.project)
              .reportError(
                notificationGroupId, name,
                CodeStyleBundle.message("async.formatting.service.timeout", name, timeout.seconds.toString())
              )
          } else if (myResult != null) {
            if (ApplicationManager.getApplication().isWriteAccessAllowed) {
              updateDocument(myResult!!)
            } else {
              ApplicationManager.getApplication().invokeLater {
                CommandProcessor.getInstance().runUndoTransparentAction {
                  try {
                    WriteAction.run(ThrowableRunnable { updateDocument(myResult!!) })
                  } catch (throwable: Throwable) {
                    logger.error(throwable)
                  }
                }
              }
            }
          }
        } catch (ie: InterruptedException) {
          logger.warn("Interrupted formatting thread.")
        }
      }
    }

    private fun updateDocument(newText: String) {
      if (document.modificationStamp > myInitialModificationStamp) {
        for (merger in DocumentMerger.EP_NAME.extensionList) {
          if (merger.updateDocument(document, newText)) break
        }
      } else {
        document.setText(newText)
      }
      PsiDocumentManager.getInstance(myContext.project).commitDocument(document)
    }

    override fun isQuickFormat(): Boolean {
      return myQuickFormat
    }

    override fun onTextReady(updatedText: String) {
      if (myStateRef.compareAndSet(FormattingRequestState.RUNNING, FormattingRequestState.COMPLETED)) {
        myResult = updatedText
        myTaskSemaphore.release()
      }
    }

    override fun onError(title: String, message: String) {
      if (myStateRef.compareAndSet(FormattingRequestState.RUNNING, FormattingRequestState.COMPLETED)) {
        myTaskSemaphore.release()
        FormattingNotificationService.getInstance(myContext.project).reportError(notificationGroupId, title, message)
      }
    }

//    override fun onError(title: String, message: String, offset: Int) { onError(title, message)    }
  }

  interface FormattingTask : Runnable {
    /**
     * Cancel the current runnable.
     * @return `true` if the runnable has been successfully cancelled, `false` otherwise.
     */
    fun cancel(): Boolean
    val isRunUnderProgress: Boolean
      /**
       * @return True if the task must be run under progress (a progress indicator is created automatically). Otherwise, the task is
       * responsible for visualizing the progress by itself, it is just started on a background thread.
       */
      get() = false
  }

  inner class FormattingProgressTask(private val myRequest: FormattingRequestImpl) :
    Task.Backgroundable(
      myRequest.context.project,
      CodeStyleBundle.message("async.formatting.service.running", name),
      true
    ) {
    override fun run(indicator: ProgressIndicator) {
      indicator.isIndeterminate = false
      indicator.fraction = 0.0
      runAsyncFormat(myRequest, indicator)
      indicator.fraction = 1.0
    }

    override fun onCancel() {
      myRequest.cancel()
    }
  }

  val logger = Logger.getInstance(AsyncDocumentFormattingService::class.java)
  val timeout = Duration.ofSeconds(5)!!
  val retryPeriod = 1000 // milliseconds
}