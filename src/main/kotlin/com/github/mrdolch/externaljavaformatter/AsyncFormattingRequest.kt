package com.github.mrdolch.externaljavaformatter

import com.intellij.formatting.FormattingContext
import com.intellij.openapi.util.TextRange
import java.io.File


/**
 * Contains formatting data and methods handling formatting results.
 */
interface AsyncFormattingRequest {
  /**
   * @return The document text to be formatted.
   */
  val documentText: String

  /**
   * @return The file in the local file system with the same content as returned by [.getDocumentText] method. If
   * originally the document isn't associated with a physical file, a temporary file is created. The method returns `null` if the
   * file can't be created. The error message is logged.
   */
  val iOFile: File?

  /**
   * @return A list of formatting ranges. It must be used by `asyncFormat()` implementation if `AsyncDocumentFormattingService` supports range formatting: [FormattingService.Feature.FORMAT_FRAGMENTS] feature.
   */
  val formattingRanges: List<TextRange?>

  /**
   * @return True if only whitespaces changes are allowed.
   */
  fun canChangeWhitespaceOnly(): Boolean

  /**
   * @return True if the service must provide a quick ad-hoc formatting rather than a long-lasting document processing.
   *
   * @see FormattingService.Feature.AD_HOC_FORMATTING
   */
  val isQuickFormat: Boolean

  /**
   * @return The current [FormattingContext]. Note: use [.getFormattingRanges] instead of
   * [FormattingContext.getFormattingRange] to get proper ranges which can be modified if formatting service supports range
   * formatting.
   */
  val context: FormattingContext

  /**
   * Call this method when resulting formatted text is available. If the original document has changed, the result will be merged with
   * an available [DocumentMerger] extension. If there are no suitable document merge extensions, the result will be ignored.
   *
   *
   * **Note:** `onTextReady()` may be called only once, subsequent calls will be ignored.
   * @param updatedText New document text.
   */
  fun onTextReady(updatedText: String)

  /**
   * Show an error notification to an end user. The notification uses [AsyncDocumentFormattingService.getNotificationGroupId].
   *
   *
   * **Note:** `onError()` may be called only once, subsequent calls will be ignored.
   *
   * @param title The notification title.
   * @param message The notification message.
   */
  fun onError(title: String, message: String)
}
