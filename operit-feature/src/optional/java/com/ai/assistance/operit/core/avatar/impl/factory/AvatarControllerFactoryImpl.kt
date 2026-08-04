package com.ai.assistance.operit.core.avatar.impl.factory

import androidx.compose.runtime.Composable
import com.ai.assistance.operit.core.avatar.common.control.AvatarController
import com.ai.assistance.operit.core.avatar.common.factory.AvatarControllerFactory
import com.ai.assistance.operit.core.avatar.common.model.AvatarModel
import com.ai.assistance.operit.core.avatar.common.model.AvatarType
import com.ai.assistance.operit.core.avatar.impl.mp4.control.rememberMp4AvatarController
import com.ai.assistance.operit.core.avatar.impl.mp4.model.Mp4AvatarModel
import com.ai.assistance.operit.core.avatar.impl.webp.control.rememberWebPAvatarController
import com.ai.assistance.operit.core.avatar.impl.webp.model.WebPAvatarModel

class AvatarControllerFactoryImpl : AvatarControllerFactory {
    @Composable
    override fun createController(model: AvatarModel): AvatarController? =
        when (model.type) {
            AvatarType.WEBP -> (model as? WebPAvatarModel)?.let { rememberWebPAvatarController(it) }
            AvatarType.MP4 -> (model as? Mp4AvatarModel)?.let { rememberMp4AvatarController(it) }
            AvatarType.DRAGONBONES,
            AvatarType.MMD,
            AvatarType.GLTF,
            AvatarType.FBX -> null
        }

    override fun canCreateController(model: AvatarModel): Boolean =
        model is WebPAvatarModel || model is Mp4AvatarModel

    override val supportedTypes: List<String> = listOf(AvatarType.WEBP.name, AvatarType.MP4.name)
}
