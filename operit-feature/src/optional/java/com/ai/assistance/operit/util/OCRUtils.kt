package com.ai.assistance.operit.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.WorkerThread
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** OCR compatibility API. OCR engines are intentionally not bundled in this build. */
object OCRUtils {
    private const val TAG = "OCRUtils"
    private const val UNAVAILABLE_MESSAGE = "OCR is not included in this build"

    /** 文本识别语言选项 */
    enum class Language {
        LATIN, // 拉丁语系（英文、法文、德文等）
        CHINESE, // 中文
        JAPANESE, // 日文
        KOREAN // 韩文
    }

    /** 识别质量选项 */
    enum class Quality {
        /** 快速、低精度，适用于预览或简单文本 */
        LOW,
        /** 慢速、高精度，会进行图像预处理以提升准确率 */
        HIGH
    }

    @WorkerThread
    suspend fun recognizeTextFromBitmap(
            bitmap: Bitmap,
            language: Language = Language.LATIN,
            quality: Quality = Quality.LOW
    ): OCRResult = unavailable(bitmap, language, quality)

    /**
     * 从Uri识别文本（同步方法）
     *
     * @param context 上下文
     * @param uri 图像的Uri
     * @param language 识别语言
     * @param quality 识别质量
     * @return 识别结果
     */
    @WorkerThread
    suspend fun recognizeTextFromUri(
            context: Context,
            uri: Uri,
            language: Language = Language.LATIN,
            quality: Quality = Quality.LOW
    ): OCRResult = unavailable(context, uri, language, quality)

    // --------- 高级API：简化的接口，直接返回文本字符串 ---------//

    /**
     * 识别图像中的文本（同时识别拉丁文和中文）
     *
     * @param context 上下文
     * @param bitmap 图像
     * @param quality 识别质量
     * @return 识别到的文本，如果失败则返回空字符串
     */
    @WorkerThread
    suspend fun recognizeText(
            context: Context,
            bitmap: Bitmap,
            quality: Quality = Quality.LOW
    ): String = unavailableText(context, bitmap, quality)

    /**
     * 识别图像中的文本（指定语言）
     *
     * @param context 上下文
     * @param bitmap 图像
     * @param language 语言
     * @param quality 识别质量
     * @return 识别到的文本，如果失败则返回空字符串
     */
    @WorkerThread
    suspend fun recognizeText(
            context: Context,
            bitmap: Bitmap,
            language: Language,
            quality: Quality = Quality.LOW
    ): String = unavailableText(context, bitmap, language, quality)

    /**
     * 从文件Uri识别文本（同时识别拉丁文和中文）
     *
     * @param context 上下文
     * @param uri 图像Uri
     * @param quality 识别质量
     * @return 识别到的文本，如果失败则返回空字符串
     */
    @WorkerThread
    suspend fun recognizeText(context: Context, uri: Uri, quality: Quality = Quality.LOW): String =
        unavailableText(context, uri, quality)

    /**
     * 扫描并提取图像中所有的文本块
     *
     * @param bitmap 要识别的图像
     * @param languages 要尝试的语言列表，按优先级排序
     * @param quality 识别质量
     * @return 识别到的所有文本块列表
     */
    @WorkerThread
    suspend fun extractTextBlocks(
            bitmap: Bitmap,
            languages: List<Language> = listOf(Language.LATIN, Language.CHINESE),
            quality: Quality = Quality.LOW
    ): List<String> = unavailableBlocks(bitmap, languages, quality)

    /**
     * 保存Bitmap到临时文件，用于OCR处理
     *
     * @param context 上下文
     * @param bitmap 位图
     * @return 临时文件
     */
    @WorkerThread
    suspend fun saveBitmapToTempFile(context: Context, bitmap: Bitmap): File? =
            withContext(Dispatchers.IO) {
                val cacheDir = context.cacheDir
                val tempFile = File(cacheDir, "ocr_temp_${System.currentTimeMillis()}.jpg")

                try {
                    FileOutputStream(tempFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
                    }
                    return@withContext tempFile
                } catch (e: IOException) {
                    AppLogger.e(TAG, "Failed to save bitmap to temp file", e)
                    return@withContext null
                }
            }

    fun closeRecognizers() = Unit

    private fun unavailable(vararg ignored: Any?): OCRResult {
        AppLogger.w(TAG, "$UNAVAILABLE_MESSAGE (${ignored.size} arguments ignored)")
        return OCRResult.Error(UNAVAILABLE_MESSAGE)
    }

    private fun unavailableText(vararg ignored: Any?): String {
        AppLogger.w(TAG, "$UNAVAILABLE_MESSAGE (${ignored.size} arguments ignored)")
        return ""
    }

    private fun unavailableBlocks(vararg ignored: Any?): List<String> {
        AppLogger.w(TAG, "$UNAVAILABLE_MESSAGE (${ignored.size} arguments ignored)")
        return emptyList()
    }

    /** OCR识别结果 */
    sealed class OCRResult {
        /** 识别成功 */
        data class Success(val text: String, val textBlocks: List<TextBlock> = emptyList()) : OCRResult() {
            fun getFullText(): String = text
            fun getStructuredText(): String = textBlocks.joinToString("\n\n") { block ->
                block.lines.joinToString("\n") { it.text }
            }.ifBlank { text }
        }

        /** 识别失败 */
        data class Error(val message: String) : OCRResult()
    }

    data class TextBlock(val text: String, val lines: List<TextLine> = emptyList())
    data class TextLine(val text: String)
}
