package com.ai.assistance.operit.data.preferences

import android.content.Context

/**
 * 默认角色卡提示词的双语数据
 */
object CharacterCardBilingualData {

    /**
     * 获取默认角色卡描述
     */
    fun getDefaultDescription(context: Context): String {
        return if (isChineseLocale(context)) {
            "WuxianPi 系统内置维修助手"
        } else {
            "Built-in WuxianPi repair assistant"
        }
    }

    /**
     * 获取默认角色设定
     */
    fun getDefaultCharacterSetting(context: Context): String {
        return if (isChineseLocale(context)) {
            "你是wuxianpi维修助手，负责诊断、维护和修复 WuxianPi 与 OpenHouse 环境。准确报告检查和工具执行结果；不确定时先核实，不编造设备状态；执行可能影响用户数据或系统可用性的操作前，明确说明影响并征得用户确认。"
        } else {
            "You are the WuxianPi Repair Assistant. Diagnose, maintain, and repair WuxianPi and OpenHouse environments. Report checks and tool results accurately, verify uncertain device state instead of inventing it, and explain the impact of potentially destructive or availability-affecting operations before asking for confirmation."
        }
    }

    /**
     * 获取默认其他内容（聊天）
     */
    fun getDefaultOtherContentChat(context: Context): String {
        return if (isChineseLocale(context)) {
            "保持简洁、清楚和可操作；区分已验证事实、推断和建议。"
        } else {
            "Be concise, clear, and actionable. Distinguish verified facts from inferences and recommendations."
        }
    }

    /**
     * 获取默认其他内容（语音）
     */
    fun getDefaultOtherContentVoice(context: Context): String {
        return if (isChineseLocale(context)) {
            """
            你是wuxianpi维修助手。
            语音回答使用简短、自然、清楚的句子，优先说明结论和下一步。
            准确复述检查与工具执行结果，不把推断说成事实。
            需要用户确认或补充信息时，一次只提出必要的问题。
            """.trimIndent()
        } else {
            """
            You are the WuxianPi Repair Assistant.
            Use short, natural, and clear sentences in voice mode. Lead with the result and next step.
            Report checks and tool output accurately; never present an inference as a verified fact.
            When confirmation or more information is required, ask only the necessary question at a time.
            """.trimIndent()
        }
    }

    /**
     * 获取角色描述标签
     */
    fun getCharacterDescriptionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "角色描述："
        } else {
            "Character Description:"
        }
    }

    /**
     * 获取性格特征标签
     */
    fun getPersonalityLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "性格特征："
        } else {
            "Personality:"
        }
    }

    /**
     * 获取场景设定标签
     */
    fun getScenarioLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "场景设定："
        } else {
            "Scenario Setting:"
        }
    }

    /**
     * 获取对话示例标签
     */
    fun getDialogueExampleLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "对话示例："
        } else {
            "Dialogue Examples:"
        }
    }

    /**
     * 获取系统提示词标签
     */
    fun getSystemPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "系统提示词："
        } else {
            "System Prompt:"
        }
    }

    /**
     * 获取历史指令标签
     */
    fun getPostHistoryInstructionsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "历史指令："
        } else {
            "Post-History Instructions:"
        }
    }

    /**
     * 获取备用问候语标签
     */
    fun getAlternateGreetingsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "备用问候语："
        } else {
            "Alternate Greetings:"
        }
    }

    /**
     * 获取深度提示词标签
     */
    fun getDepthPromptLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "深度提示词："
        } else {
            "Depth Prompt:"
        }
    }

    /**
     * 获取世界书标签名称模板
     */
    fun getWorldBookTagName(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "世界书: $characterName"
        } else {
            "World Book: $characterName"
        }
    }

    /**
     * 获取世界书标签描述模板
     */
    fun getWorldBookTagDescription(context: Context, characterName: String): String {
        return if (isChineseLocale(context)) {
            "为角色'$characterName'自动生成的世界书。"
        } else {
            "World book auto-generated for character '$characterName'."
        }
    }

    /**
     * 获取来源标签
     */
    fun getSourceLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "来源：酒馆角色卡\n"
        } else {
            "Source: Tavern Character Card\n"
        }
    }

    /**
     * 获取作者标签
     */
    fun getAuthorLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者："
        } else {
            "Author:"
        }
    }

    /**
     * 获取作者备注标签
     */
    fun getAuthorNotesLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "作者备注：\n\n"
        } else {
            "Author Notes:\n\n"
        }
    }

    /**
     * 获取版本标签
     */
    fun getVersionLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "版本："
        } else {
            "Version:"
        }
    }

    /**
     * 获取原始标签标签
     */
    fun getOriginalTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "原始标签："
        } else {
            "Original Tags:"
        }
    }

    /**
     * 获取格式标签
     */
    fun getFormatLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "格式："
        } else {
            "Format:"
        }
    }

    /**
     * 获取标签标签
     */
    fun getTagsLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "标签："
        } else {
            "Tags:"
        }
    }

    /**
     * 获取等标签
     */
    fun getEtAlLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "等"
        } else {
            " et al."
        }
    }

    /**
     * 获取未找到标签
     */
    fun getNotFoundLabel(context: Context): String {
        return if (isChineseLocale(context)) {
            "未找到"
        } else {
            "not found"
        }
    }

    /**
     * 检查是否为中文语言环境
     */
    private fun isChineseLocale(context: Context): Boolean {
        val locale = context.resources.configuration.locales.get(0)
        return locale.language == "zh" || locale.language == "zho"
    }
}
