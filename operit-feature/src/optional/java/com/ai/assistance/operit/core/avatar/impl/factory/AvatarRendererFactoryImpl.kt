package com.ai.assistance.operit.core.avatar.impl.factory

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ai.assistance.operit.core.avatar.common.control.AvatarController
import com.ai.assistance.operit.core.avatar.common.factory.AvatarRendererFactory
import com.ai.assistance.operit.core.avatar.common.model.AvatarModel
import com.ai.assistance.operit.core.avatar.common.model.AvatarType
import com.ai.assistance.operit.core.avatar.impl.mp4.model.Mp4AvatarModel
import com.ai.assistance.operit.core.avatar.impl.mp4.view.Mp4Renderer
import com.ai.assistance.operit.core.avatar.impl.webp.model.WebPAvatarModel
import com.ai.assistance.operit.core.avatar.impl.webp.view.WebPRenderer

class AvatarRendererFactoryImpl : AvatarRendererFactory {
    @Composable
    override fun createRenderer(
        model: AvatarModel,
    ): @Composable ((modifier: Modifier, controller: AvatarController) -> Unit)? =
        when (model.type) {
            AvatarType.WEBP -> (model as? WebPAvatarModel)?.let { webp ->
                { modifier, controller -> WebPRenderer(modifier, webp, controller, onError = {}) }
            }
            AvatarType.MP4 -> (model as? Mp4AvatarModel)?.let { mp4 ->
                { modifier, controller -> Mp4Renderer(modifier, mp4, controller, onError = {}) }
            }
            AvatarType.DRAGONBONES,
            AvatarType.MMD,
            AvatarType.GLTF,
            AvatarType.FBX -> null
        }
}
