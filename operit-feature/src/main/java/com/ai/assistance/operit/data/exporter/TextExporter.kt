package com.ai.assistance.operit.data.exporter

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.model.ChatHistory
import com.ai.assistance.operit.data.model.ChatMessage
import java.time.format.DateTimeFormatter

/**
 * 纯文本格式导出器
 */
object TextExporter {

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    /**
     * 导出单个对话为纯文本
     */
    fun exportSingle(context: Context, chatHistory: ChatHistory): String {
        val sb = StringBuilder()

        // 标题
        sb.appendLine("=" .repeat(60))
        sb.appendLine(chatHistory.title.center(60))
        sb.appendLine("=".repeat(60))
        sb.appendLine()

        // 元信息
        sb.appendLine(
            context.getString(
                R.string.export_created_time,
                chatHistory.createdAt.format(dateFormatter)
            )
        )
        sb.appendLine(
            context.getString(
                R.string.export_updated_time,
                chatHistory.updatedAt.format(dateFormatter)
            )
        )
        if (chatHistory.group != null) {
            sb.appendLine(context.getString(R.string.export_group, chatHistory.group))
        }
        sb.appendLine(context.getString(R.string.export_message_count, chatHistory.messages.size))
        sb.appendLine()
        sb.appendLine("-".repeat(60))
        sb.appendLine()

        // 消息内容
        for ((index, message) in chatHistory.messages.withIndex()) {
            if (index > 0) {
                sb.appendLine()
            }
            appendMessage(context, sb, message)
        }

        sb.appendLine()
        sb.appendLine("=".repeat(60))

        return sb.toString()
    }

    /**
     * 导出多个对话为纯文本
     */
    fun exportMultiple(context: Context, chatHistories: List<ChatHistory>): String {
        val sb = StringBuilder()

        // 总览信息
        sb.appendLine("=" .repeat(60))
        sb.appendLine(context.getString(R.string.export_chat_history).center(60))
        sb.appendLine("=".repeat(60))
        sb.appendLine()
        sb.appendLine(
            context.getString(
                R.string.export_export_time,
                java.time.LocalDateTime.now().format(dateFormatter)
            )
        )
        sb.appendLine(context.getString(R.string.export_conversation_count, chatHistories.size))
        sb.appendLine(
            context.getString(
                R.string.export_total_message_count,
                chatHistories.sumOf { it.messages.size }
            )
        )
        sb.appendLine()
        sb.appendLine("=".repeat(60))
        sb.appendLine()
        sb.appendLine()

        for ((index, chatHistory) in chatHistories.withIndex()) {
            if (index > 0) {
                sb.appendLine()
                sb.appendLine()
            }

            sb.append(exportSingle(context, chatHistory))
        }

        sb.appendLine()
        sb.appendLine()
        sb.appendLine(context.getString(R.string.export_completed))

        return sb.toString()
    }

    /**
     * 添加单条消息
     */
    private fun appendMessage(context: Context, sb: StringBuilder, message: ChatMessage) {
        val roleIcon = if (message.sender == "user") "👤" else "🤖"
        val roleText =
            if (message.sender == "user") {
                context.getString(R.string.export_user)
            } else {
                context.getString(R.string.export_assistant)
            }

        sb.appendLine("[$roleIcon $roleText]")

        if (message.modelName.isNotEmpty() && message.modelName != "markdown" && message.modelName != "unknown") {
            sb.appendLine(context.getString(R.string.export_model, message.modelName))
        }

        sb.appendLine()
        sb.appendLine(message.content)
        sb.appendLine()
        sb.appendLine("-".repeat(60))
    }

    /**
     * 字符串居中扩展函数
     */
    private fun String.center(width: Int): String {
        if (this.length >= width) return this
        val padding = width - this.length
        val leftPad = padding / 2
        val rightPad = padding - leftPad
        return " ".repeat(leftPad) + this + " ".repeat(rightPad)
    }
}
