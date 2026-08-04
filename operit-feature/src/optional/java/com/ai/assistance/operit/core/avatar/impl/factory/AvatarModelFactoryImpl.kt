package com.ai.assistance.operit.core.avatar.impl.factory

import com.ai.assistance.operit.core.avatar.common.factory.AvatarModelFactory
import com.ai.assistance.operit.core.avatar.common.model.AvatarModel
import com.ai.assistance.operit.core.avatar.common.model.AvatarType
import com.ai.assistance.operit.core.avatar.common.state.AvatarEmotion
import com.ai.assistance.operit.core.avatar.impl.mp4.model.Mp4AvatarModel
import com.ai.assistance.operit.core.avatar.impl.webp.model.WebPAvatarModel
import java.io.File

class AvatarModelFactoryImpl : AvatarModelFactory {
    override fun createModel(
        id: String,
        name: String,
        type: AvatarType,
        data: Map<String, Any>,
    ): AvatarModel? =
        when (type) {
            AvatarType.WEBP -> createWebPModel(id, name, data)
            AvatarType.MP4 -> createMp4Model(id, name, data)
            AvatarType.DRAGONBONES,
            AvatarType.MMD,
            AvatarType.GLTF,
            AvatarType.FBX -> null
        }

    override fun createModelFromData(dataModel: Any): AvatarModel? {
        val data = dataModel as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val typed = data as? Map<String, Any> ?: return null
        val id = typed["id"] as? String ?: return null
        val name = typed["name"] as? String ?: return null
        val type = runCatching { AvatarType.valueOf(typed["type"] as? String ?: return null) }.getOrNull()
            ?: return null
        return createModel(id, name, type, typed)
    }

    override fun createDefaultModel(type: AvatarType, baseName: String): AvatarModel? =
        when (type) {
            AvatarType.WEBP -> WebPAvatarModel.createStandard("default_webp", baseName, "assets/avatars/default")
            AvatarType.MP4 -> Mp4AvatarModel.createStandard("default_mp4", baseName, "assets/avatars/default")
            AvatarType.DRAGONBONES,
            AvatarType.MMD,
            AvatarType.GLTF,
            AvatarType.FBX -> null
        }

    override fun validateData(type: AvatarType, data: Map<String, Any>): Boolean =
        type in supportedTypes && getRequiredDataKeys(type).all { data[it] != null }

    override val supportedTypes: List<AvatarType> = listOf(AvatarType.WEBP, AvatarType.MP4)

    override fun getRequiredDataKeys(type: AvatarType): List<String> =
        when (type) {
            AvatarType.WEBP,
            AvatarType.MP4 -> listOf("basePath")
            AvatarType.DRAGONBONES,
            AvatarType.MMD,
            AvatarType.GLTF,
            AvatarType.FBX -> emptyList()
        }

    private fun createWebPModel(id: String, name: String, data: Map<String, Any>): AvatarModel? =
        createMediaModel(data, "webpFiles", setOf("webp")) { basePath, emotionMap, availableFiles ->
            if (emotionMap.isNotEmpty() || availableFiles.isNotEmpty()) {
                WebPAvatarModel(id, name, basePath, emotionMap, availableFiles.ifEmpty { emotionMap.values.toList() }, parseCurrentEmotion(data))
            } else {
                WebPAvatarModel.createStandard(id, name, basePath)
            }
        }

    private fun createMp4Model(id: String, name: String, data: Map<String, Any>): AvatarModel? =
        createMediaModel(data, "mp4Files", setOf("mp4")) { basePath, emotionMap, availableFiles ->
            if (emotionMap.isNotEmpty() || availableFiles.isNotEmpty()) {
                Mp4AvatarModel(id, name, basePath, emotionMap, availableFiles.ifEmpty { emotionMap.values.toList() }, parseCurrentEmotion(data))
            } else {
                Mp4AvatarModel.createStandard(id, name, basePath)
            }
        }

    private inline fun createMediaModel(
        data: Map<String, Any>,
        listKey: String,
        extensions: Set<String>,
        create: (String, Map<AvatarEmotion, String>, List<String>) -> AvatarModel,
    ): AvatarModel? = runCatching {
        val basePath = data["basePath"] as? String ?: return null
        val files = (data[listKey] as? List<*>)
            .orEmpty()
            .mapNotNull { it as? String }
            .filter { File(it).extension.lowercase() in extensions }
            .distinct()
        val explicit = (data["emotionToFileMap"] as? Map<*, *>)
            .orEmpty()
            .mapNotNull { (key, value) ->
                val emotion = runCatching { AvatarEmotion.valueOf(key.toString().uppercase()) }.getOrNull()
                val fileName = value?.toString()?.takeIf { it.isNotBlank() }
                if (emotion != null && fileName != null) emotion to fileName else null
            }
            .toMap()
        val inferred = explicit.ifEmpty { inferEmotionMap(files) }
        create(basePath, inferred, files)
    }.getOrNull()

    private fun inferEmotionMap(files: List<String>): Map<AvatarEmotion, String> {
        if (files.isEmpty()) return emptyMap()
        val byName = files.associateBy { File(it).nameWithoutExtension.lowercase() }
        val aliases = linkedMapOf(
            AvatarEmotion.IDLE to listOf("idle", "default", "normal", "standby"),
            AvatarEmotion.LISTENING to listOf("listening", "talking", "speak", "speaking", "chat"),
            AvatarEmotion.THINKING to listOf("thinking", "think", "loading"),
            AvatarEmotion.HAPPY to listOf("happy", "smile", "joy"),
            AvatarEmotion.SAD to listOf("sad", "cry", "crying", "angry", "mad"),
            AvatarEmotion.CONFUSED to listOf("confused", "shy", "embarrassed"),
            AvatarEmotion.SURPRISED to listOf("surprised", "surprise", "wow"),
        )
        val matched = aliases.mapNotNull { (emotion, names) ->
            names.firstNotNullOfOrNull(byName::get)?.let { emotion to it }
        }.toMap()
        return matched.ifEmpty { mapOf(AvatarEmotion.IDLE to files.first()) }
    }

    private fun parseCurrentEmotion(data: Map<String, Any>): AvatarEmotion =
        runCatching { AvatarEmotion.valueOf(data["currentEmotion"].toString().uppercase()) }
            .getOrDefault(AvatarEmotion.IDLE)
}
