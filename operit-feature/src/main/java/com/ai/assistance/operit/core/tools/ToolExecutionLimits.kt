package com.ai.assistance.operit.core.tools

object ToolExecutionLimits {
    const val MAX_FILE_READ_BYTES = 32_000
    const val DEFAULT_FILE_READ_PART_LINES = 200
    const val MAX_TEXT_RESULT_LENGTH = 5_000
    const val MAX_FINAL_TOOL_RESULT_MESSAGE_CHARS = MAX_FILE_READ_BYTES * 2
}
