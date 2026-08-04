package com.ai.assistance.operit.ui.features.settings.screens.theme

import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.DisplayPreferencesManager
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.ui.features.settings.components.ColorPickerDialog
import com.ai.assistance.operit.ui.features.settings.sections.ThemeSettingsAvatarSection
import com.ai.assistance.operit.ui.features.settings.sections.ThemeSettingsChatStyleSection
import com.ai.assistance.operit.ui.features.settings.sections.ThemeSettingsDisplayOptionsSection
import com.ai.assistance.operit.ui.features.settings.sections.SaveThemeSettingsAction
import com.ai.assistance.operit.ui.theme.getTextColorForBackground
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.FileUtils
import com.canhub.cropper.CropImageContract
import com.canhub.cropper.CropImageContractOptions
import com.canhub.cropper.CropImageOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class NinePatchBubbleAutoParams(
    val cropLeftRatio: Float,
    val cropTopRatio: Float,
    val cropRightRatio: Float,
    val cropBottomRatio: Float,
    val repeatXStartRatio: Float,
    val repeatXEndRatio: Float,
    val repeatYStartRatio: Float,
    val repeatYEndRatio: Float,
)

private enum class ThemeSettingsBubbleTarget {
    USER,
    AI,
}

private enum class ThemeSettingsAvatarPickerMode(val uniqueName: String) {
    USER("user_avatar"),
    AI("ai_avatar"),
    GLOBAL_USER("global_user_avatar");

    companion object {
        fun fromKey(key: String): ThemeSettingsAvatarPickerMode =
            when (key) {
                "user" -> USER
                "ai" -> AI
                "global_user" -> GLOBAL_USER
                else -> error("Unsupported avatar picker mode: $key")
            }
    }
}

internal data class ThemeSettingsChatRuntimeState(
    val context: android.content.Context,
    val scope: CoroutineScope,
    val preferencesManager: UserPreferencesManager,
    val displayPreferencesManager: DisplayPreferencesManager,
    val saveThemeSettingsWithCharacterCard: SaveThemeSettingsAction,
    val bubbleUserBubbleLiquidGlassInput: Boolean,
    val bubbleUserBubbleWaterGlassInput: Boolean,
    val bubbleAiBubbleLiquidGlassInput: Boolean,
    val bubbleAiBubbleWaterGlassInput: Boolean,
    val onBubbleUserUseImageInputChange: (Boolean) -> Unit,
    val onBubbleAiUseImageInputChange: (Boolean) -> Unit,
    val onBubbleUserImageUriInputChange: (String?) -> Unit,
    val onBubbleAiImageUriInputChange: (String?) -> Unit,
    val onBubbleUserImageCropLeftInputChange: (Float) -> Unit,
    val onBubbleUserImageCropTopInputChange: (Float) -> Unit,
    val onBubbleUserImageCropRightInputChange: (Float) -> Unit,
    val onBubbleUserImageCropBottomInputChange: (Float) -> Unit,
    val onBubbleUserImageRepeatStartInputChange: (Float) -> Unit,
    val onBubbleUserImageRepeatEndInputChange: (Float) -> Unit,
    val onBubbleUserImageRepeatYStartInputChange: (Float) -> Unit,
    val onBubbleUserImageRepeatYEndInputChange: (Float) -> Unit,
    val onBubbleUserImageScaleInputChange: (Float) -> Unit,
    val onBubbleAiImageCropLeftInputChange: (Float) -> Unit,
    val onBubbleAiImageCropTopInputChange: (Float) -> Unit,
    val onBubbleAiImageCropRightInputChange: (Float) -> Unit,
    val onBubbleAiImageCropBottomInputChange: (Float) -> Unit,
    val onBubbleAiImageRepeatStartInputChange: (Float) -> Unit,
    val onBubbleAiImageRepeatEndInputChange: (Float) -> Unit,
    val onBubbleAiImageRepeatYStartInputChange: (Float) -> Unit,
    val onBubbleAiImageRepeatYEndInputChange: (Float) -> Unit,
    val onBubbleAiImageScaleInputChange: (Float) -> Unit,
    val onBubbleImageRenderModeInputChange: (String) -> Unit,
    val onUserAvatarUriInputChange: (String?) -> Unit,
    val onAiAvatarUriInputChange: (String?) -> Unit,
    val onGlobalUserAvatarUriInputChange: (String?) -> Unit,
)

internal data class ThemeSettingsChatRuntime(
    val onPickBubbleUserImage: () -> Unit,
    val onPickBubbleAiImage: () -> Unit,
    val onClearBubbleUserImage: () -> Unit,
    val onClearBubbleAiImage: () -> Unit,
    val avatarImagePicker: ManagedActivityResultLauncher<String, Uri?>,
    val onAvatarPickerModeChange: (String) -> Unit,
)

private fun isNinePatchMarker(colorInt: Int): Boolean {
    val alpha = (colorInt ushr 24) and 0xFF
    if (alpha < 0x80) return false
    val red = (colorInt ushr 16) and 0xFF
    val green = (colorInt ushr 8) and 0xFF
    val blue = colorInt and 0xFF
    return red < 32 && green < 32 && blue < 32
}

private fun buildStretchRange(marked: List<Int>, innerSize: Int): Pair<Float, Float>? {
    if (marked.isEmpty() || innerSize <= 0) return null
    val start = marked.first().toFloat() / innerSize.toFloat()
    val endExclusive = (marked.last() + 1).toFloat() / innerSize.toFloat()
    return start.coerceIn(0f, 1f) to endExclusive.coerceIn(0f, 1f)
}

private suspend fun parseNinePatchBubbleParams(
    context: android.content.Context,
    uri: Uri,
): NinePatchBubbleAutoParams? =
    withContext(Dispatchers.IO) {
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
            }
        }.getOrNull()?.let { bitmap ->
            val width = bitmap.width
            val height = bitmap.height
            if (width < 3 || height < 3) return@let null

            val innerWidth = width - 2
            val innerHeight = height - 2
            if (innerWidth <= 0 || innerHeight <= 0) return@let null

            val topMarkers = mutableListOf<Int>()
            val leftMarkers = mutableListOf<Int>()
            for (x in 0 until innerWidth) {
                if (isNinePatchMarker(bitmap.getPixel(x + 1, 0))) {
                    topMarkers.add(x)
                }
            }
            for (y in 0 until innerHeight) {
                if (isNinePatchMarker(bitmap.getPixel(0, y + 1))) {
                    leftMarkers.add(y)
                }
            }

            val xRange = buildStretchRange(topMarkers, innerWidth) ?: (0.35f to 0.65f)
            val yRange = buildStretchRange(leftMarkers, innerHeight) ?: (0.35f to 0.65f)

            NinePatchBubbleAutoParams(
                cropLeftRatio = (1f / width.toFloat()).coerceIn(0f, 0.45f),
                cropTopRatio = (1f / height.toFloat()).coerceIn(0f, 0.45f),
                cropRightRatio = (1f / width.toFloat()).coerceIn(0f, 0.45f),
                cropBottomRatio = (1f / height.toFloat()).coerceIn(0f, 0.45f),
                repeatXStartRatio = xRange.first,
                repeatXEndRatio = xRange.second,
                repeatYStartRatio = yRange.first,
                repeatYEndRatio = yRange.second,
            )
        }
    }

private fun resolveDisplayName(context: android.content.Context, uri: Uri): String? {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
    }.getOrNull()
}

private fun isNinePatchPngUri(context: android.content.Context, uri: Uri): Boolean {
    val displayName = resolveDisplayName(context, uri)?.lowercase()
    if (displayName != null && displayName.endsWith(".9.png")) {
        return true
    }
    val pathName = uri.lastPathSegment?.lowercase()
    return pathName?.endsWith(".9.png") == true
}

@Composable
internal fun rememberThemeSettingsChatRuntime(
    state: ThemeSettingsChatRuntimeState,
): ThemeSettingsChatRuntime {
    val context = state.context
    var bubbleImagePickerTarget by remember { mutableStateOf(ThemeSettingsBubbleTarget.USER) }
    val bubbleImageCropLauncher =
        rememberLauncherForActivityResult(CropImageContract()) { result ->
            if (result.isSuccessful) {
                val croppedUri = result.uriContent
                if (croppedUri != null) {
                    state.scope.launch {
                        val uniqueName =
                            when (bubbleImagePickerTarget) {
                                ThemeSettingsBubbleTarget.AI -> "bubble_ai"
                                ThemeSettingsBubbleTarget.USER -> "bubble_user"
                            }
                        val internalUri =
                            FileUtils.copyFileToInternalStorage(context, croppedUri, uniqueName)
                        if (internalUri != null) {
                            val enabledForAi =
                                !state.bubbleAiBubbleLiquidGlassInput &&
                                    !state.bubbleAiBubbleWaterGlassInput
                            val enabledForUser =
                                !state.bubbleUserBubbleLiquidGlassInput &&
                                    !state.bubbleUserBubbleWaterGlassInput
                            when (bubbleImagePickerTarget) {
                                ThemeSettingsBubbleTarget.AI -> {
                                    state.onBubbleAiImageUriInputChange(internalUri.toString())
                                    state.onBubbleAiUseImageInputChange(enabledForAi)
                                    state.saveThemeSettingsWithCharacterCard {
                                        state.preferencesManager.saveThemeSettings(
                                            bubbleAiImageUri = internalUri.toString(),
                                            bubbleAiUseImage = enabledForAi,
                                        )
                                    }
                                }
                                ThemeSettingsBubbleTarget.USER -> {
                                    state.onBubbleUserImageUriInputChange(internalUri.toString())
                                    state.onBubbleUserUseImageInputChange(enabledForUser)
                                    state.saveThemeSettingsWithCharacterCard {
                                        state.preferencesManager.saveThemeSettings(
                                            bubbleUserImageUri = internalUri.toString(),
                                            bubbleUserUseImage = enabledForUser,
                                        )
                                    }
                                }
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.chat_style_bubble_image_saved),
                                Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            Toast.makeText(
                                context,
                                context.getString(R.string.theme_copy_failed),
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                    }
                }
            } else if (result.error != null) {
                Toast.makeText(
                    context,
                    context.getString(R.string.theme_image_crop_failed, result.error!!.message),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }

    fun launchBubbleImageCrop(uri: Uri) {
        val cropOptions =
            CropImageContractOptions(
                uri,
                CropImageOptions().apply {
                    guidelines = com.canhub.cropper.CropImageView.Guidelines.ON
                    outputCompressFormat = android.graphics.Bitmap.CompressFormat.PNG
                    outputCompressQuality = 90
                    fixAspectRatio = false
                    cropMenuCropButtonTitle = context.getString(R.string.theme_crop_done)
                    activityTitle = context.getString(R.string.theme_crop_image)
                    showCropOverlay = true
                    showProgressBar = true
                    multiTouchEnabled = true
                    autoZoomEnabled = true
                },
            )
        bubbleImageCropLauncher.launch(cropOptions)
    }

    val bubbleImagePickerLauncher =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                if (!isNinePatchPngUri(context, uri)) {
                    launchBubbleImageCrop(uri)
                    return@rememberLauncherForActivityResult
                }

                state.scope.launch {
                    val uniqueName =
                        when (bubbleImagePickerTarget) {
                            ThemeSettingsBubbleTarget.AI -> "bubble_ai"
                            ThemeSettingsBubbleTarget.USER -> "bubble_user"
                        }
                    val internalUri =
                        FileUtils.copyFileToInternalStorage(context, uri, uniqueName)
                    if (internalUri == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.theme_copy_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }

                    val autoParams =
                        parseNinePatchBubbleParams(context, uri)
                            ?: parseNinePatchBubbleParams(context, internalUri)
                    if (autoParams == null) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.theme_copy_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                        return@launch
                    }

                    val internalUriString = internalUri.toString()
                    val renderMode = UserPreferencesManager.BUBBLE_IMAGE_RENDER_MODE_NINE_PATCH
                    state.onBubbleImageRenderModeInputChange(renderMode)
                    when (bubbleImagePickerTarget) {
                        ThemeSettingsBubbleTarget.AI -> {
                            val useImage =
                                !state.bubbleAiBubbleLiquidGlassInput &&
                                    !state.bubbleAiBubbleWaterGlassInput
                            state.onBubbleAiImageUriInputChange(internalUriString)
                            state.onBubbleAiUseImageInputChange(useImage)
                            state.onBubbleAiImageCropLeftInputChange(autoParams.cropLeftRatio)
                            state.onBubbleAiImageCropTopInputChange(autoParams.cropTopRatio)
                            state.onBubbleAiImageCropRightInputChange(autoParams.cropRightRatio)
                            state.onBubbleAiImageCropBottomInputChange(autoParams.cropBottomRatio)
                            state.onBubbleAiImageRepeatStartInputChange(autoParams.repeatXStartRatio)
                            state.onBubbleAiImageRepeatEndInputChange(autoParams.repeatXEndRatio)
                            state.onBubbleAiImageRepeatYStartInputChange(autoParams.repeatYStartRatio)
                            state.onBubbleAiImageRepeatYEndInputChange(autoParams.repeatYEndRatio)
                            state.onBubbleAiImageScaleInputChange(1f)
                            state.saveThemeSettingsWithCharacterCard {
                                state.preferencesManager.saveThemeSettings(
                                    bubbleAiImageUri = internalUriString,
                                    bubbleAiUseImage = useImage,
                                    bubbleAiImageCropLeft = autoParams.cropLeftRatio,
                                    bubbleAiImageCropTop = autoParams.cropTopRatio,
                                    bubbleAiImageCropRight = autoParams.cropRightRatio,
                                    bubbleAiImageCropBottom = autoParams.cropBottomRatio,
                                    bubbleAiImageRepeatStart = autoParams.repeatXStartRatio,
                                    bubbleAiImageRepeatEnd = autoParams.repeatXEndRatio,
                                    bubbleAiImageRepeatYStart = autoParams.repeatYStartRatio,
                                    bubbleAiImageRepeatYEnd = autoParams.repeatYEndRatio,
                                    bubbleAiImageScale = 1f,
                                    bubbleImageRenderMode = renderMode,
                                )
                            }
                        }
                        ThemeSettingsBubbleTarget.USER -> {
                            val useImage =
                                !state.bubbleUserBubbleLiquidGlassInput &&
                                    !state.bubbleUserBubbleWaterGlassInput
                            state.onBubbleUserImageUriInputChange(internalUriString)
                            state.onBubbleUserUseImageInputChange(useImage)
                            state.onBubbleUserImageCropLeftInputChange(autoParams.cropLeftRatio)
                            state.onBubbleUserImageCropTopInputChange(autoParams.cropTopRatio)
                            state.onBubbleUserImageCropRightInputChange(autoParams.cropRightRatio)
                            state.onBubbleUserImageCropBottomInputChange(autoParams.cropBottomRatio)
                            state.onBubbleUserImageRepeatStartInputChange(autoParams.repeatXStartRatio)
                            state.onBubbleUserImageRepeatEndInputChange(autoParams.repeatXEndRatio)
                            state.onBubbleUserImageRepeatYStartInputChange(autoParams.repeatYStartRatio)
                            state.onBubbleUserImageRepeatYEndInputChange(autoParams.repeatYEndRatio)
                            state.onBubbleUserImageScaleInputChange(1f)
                            state.saveThemeSettingsWithCharacterCard {
                                state.preferencesManager.saveThemeSettings(
                                    bubbleUserImageUri = internalUriString,
                                    bubbleUserUseImage = useImage,
                                    bubbleUserImageCropLeft = autoParams.cropLeftRatio,
                                    bubbleUserImageCropTop = autoParams.cropTopRatio,
                                    bubbleUserImageCropRight = autoParams.cropRightRatio,
                                    bubbleUserImageCropBottom = autoParams.cropBottomRatio,
                                    bubbleUserImageRepeatStart = autoParams.repeatXStartRatio,
                                    bubbleUserImageRepeatEnd = autoParams.repeatXEndRatio,
                                    bubbleUserImageRepeatYStart = autoParams.repeatYStartRatio,
                                    bubbleUserImageRepeatYEnd = autoParams.repeatYEndRatio,
                                    bubbleUserImageScale = 1f,
                                    bubbleImageRenderMode = renderMode,
                                )
                            }
                        }
                    }

                    Toast.makeText(
                        context,
                        context.getString(R.string.chat_style_bubble_image_saved),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            }
        }

    var avatarPickerMode by remember { mutableStateOf(ThemeSettingsAvatarPickerMode.USER) }
    val cropAvatarLauncher = rememberLauncherForActivityResult(CropImageContract()) { result ->
        if (result.isSuccessful) {
            val croppedUri = result.uriContent
            if (croppedUri != null) {
                state.scope.launch {
                    val internalUri =
                        FileUtils.copyFileToInternalStorage(
                            context,
                            croppedUri,
                            avatarPickerMode.uniqueName,
                        )
                    if (internalUri != null) {
                        when (avatarPickerMode) {
                            ThemeSettingsAvatarPickerMode.USER -> {
                                AppLogger.d("ThemeSettings", "User avatar saved to: $internalUri")
                                state.onUserAvatarUriInputChange(internalUri.toString())
                                state.saveThemeSettingsWithCharacterCard {
                                    state.preferencesManager.saveThemeSettings(
                                        customUserAvatarUri = internalUri.toString(),
                                    )
                                }
                            }
                            ThemeSettingsAvatarPickerMode.AI -> {
                                AppLogger.d("ThemeSettings", "AI avatar saved to: $internalUri")
                                state.onAiAvatarUriInputChange(internalUri.toString())
                                state.saveThemeSettingsWithCharacterCard {
                                    state.preferencesManager.saveThemeSettings(
                                        customAiAvatarUri = internalUri.toString(),
                                    )
                                }
                            }
                            ThemeSettingsAvatarPickerMode.GLOBAL_USER -> {
                                AppLogger.d(
                                    "ThemeSettings",
                                    "Global user avatar saved to: $internalUri",
                                )
                                state.onGlobalUserAvatarUriInputChange(internalUri.toString())
                                state.displayPreferencesManager.saveDisplaySettings(
                                    globalUserAvatarUri = internalUri.toString(),
                                )
                            }
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.avatar_updated),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.theme_copy_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        } else if (result.error != null) {
            Toast.makeText(
                context,
                context.getString(R.string.avatar_crop_failed, result.error!!.message),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    fun launchAvatarCrop(uri: Uri) {
        val cropOptions =
            CropImageContractOptions(
                uri,
                CropImageOptions().apply {
                    guidelines = com.canhub.cropper.CropImageView.Guidelines.ON
                    outputCompressFormat = android.graphics.Bitmap.CompressFormat.PNG
                    outputCompressQuality = 90
                    fixAspectRatio = true
                    aspectRatioX = 1
                    aspectRatioY = 1
                    cropMenuCropButtonTitle = context.getString(R.string.theme_crop_done)
                    activityTitle = context.getString(R.string.crop_avatar)
                    toolbarColor = Color.Gray.toArgb()
                    toolbarTitleColor = Color.White.toArgb()
                },
            )
        cropAvatarLauncher.launch(cropOptions)
    }

    val avatarImagePicker =
        rememberLauncherForActivityResult(contract = ActivityResultContracts.GetContent()) { uri: Uri? ->
            if (uri != null) {
                launchAvatarCrop(uri)
            }
        }

    return ThemeSettingsChatRuntime(
        onPickBubbleUserImage = {
            bubbleImagePickerTarget = ThemeSettingsBubbleTarget.USER
            bubbleImagePickerLauncher.launch("image/*")
        },
        onPickBubbleAiImage = {
            bubbleImagePickerTarget = ThemeSettingsBubbleTarget.AI
            bubbleImagePickerLauncher.launch("image/*")
        },
        onClearBubbleUserImage = {
            state.onBubbleUserImageUriInputChange(null)
            state.onBubbleUserUseImageInputChange(false)
            state.saveThemeSettingsWithCharacterCard {
                state.preferencesManager.saveThemeSettings(
                    bubbleUserImageUri = "",
                    bubbleUserUseImage = false,
                )
            }
        },
        onClearBubbleAiImage = {
            state.onBubbleAiImageUriInputChange(null)
            state.onBubbleAiUseImageInputChange(false)
            state.saveThemeSettingsWithCharacterCard {
                state.preferencesManager.saveThemeSettings(
                    bubbleAiImageUri = "",
                    bubbleAiUseImage = false,
                )
            }
        },
        avatarImagePicker = avatarImagePicker,
        onAvatarPickerModeChange = {
            avatarPickerMode = ThemeSettingsAvatarPickerMode.fromKey(it)
        },
    )
}

@Composable
internal fun ThemeSettingsChatTab(
    shared: ThemeSettingsShared,
    cardColors: androidx.compose.material3.CardColors,
) {
    val preferencesManager = shared.preferencesManager
    val displayPreferencesManager = shared.displayPreferencesManager
    val defaultCursorUserBubbleColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val defaultBubbleUserBubbleColor = MaterialTheme.colorScheme.primaryContainer.toArgb()
    val defaultBubbleAiBubbleColor = MaterialTheme.colorScheme.surface.toArgb()

    val chatStyle by preferencesManager.chatStyle.collectAsState(
        initial = UserPreferencesManager.CHAT_STYLE_CURSOR,
    )
    val inputStyle by preferencesManager.inputStyle.collectAsState(
        initial = UserPreferencesManager.INPUT_STYLE_AGENT,
    )
    val bubbleShowAvatar by preferencesManager.bubbleShowAvatar.collectAsState(initial = true)
    val bubbleWideLayoutEnabled by preferencesManager.bubbleWideLayoutEnabled.collectAsState(initial = false)
    val cursorUserBubbleFollowTheme by preferencesManager.cursorUserBubbleFollowTheme.collectAsState(initial = true)
    val cursorUserBubbleLiquidGlass by preferencesManager.cursorUserBubbleLiquidGlass.collectAsState(initial = false)
    val cursorUserBubbleWaterGlass by preferencesManager.cursorUserBubbleWaterGlass.collectAsState(initial = false)
    val bubbleUserBubbleLiquidGlass by preferencesManager.bubbleUserBubbleLiquidGlass.collectAsState(initial = false)
    val bubbleUserBubbleWaterGlass by preferencesManager.bubbleUserBubbleWaterGlass.collectAsState(initial = false)
    val bubbleAiBubbleLiquidGlass by preferencesManager.bubbleAiBubbleLiquidGlass.collectAsState(initial = false)
    val bubbleAiBubbleWaterGlass by preferencesManager.bubbleAiBubbleWaterGlass.collectAsState(initial = false)
    val cursorUserBubbleColor by preferencesManager.cursorUserBubbleColor.collectAsState(initial = null)
    val bubbleUserBubbleColor by preferencesManager.bubbleUserBubbleColor.collectAsState(initial = null)
    val bubbleAiBubbleColor by preferencesManager.bubbleAiBubbleColor.collectAsState(initial = null)
    val bubbleUserTextColor by preferencesManager.bubbleUserTextColor.collectAsState(initial = null)
    val bubbleAiTextColor by preferencesManager.bubbleAiTextColor.collectAsState(initial = null)
    val bubbleUserUseCustomFont by preferencesManager.bubbleUserUseCustomFont.collectAsState(initial = false)
    val bubbleUserFontType by preferencesManager.bubbleUserFontType.collectAsState(
        initial = UserPreferencesManager.FONT_TYPE_SYSTEM,
    )
    val bubbleUserSystemFontName by preferencesManager.bubbleUserSystemFontName.collectAsState(
        initial = UserPreferencesManager.SYSTEM_FONT_DEFAULT,
    )
    val bubbleUserCustomFontPath by preferencesManager.bubbleUserCustomFontPath.collectAsState(initial = null)
    val bubbleAiUseCustomFont by preferencesManager.bubbleAiUseCustomFont.collectAsState(initial = false)
    val bubbleAiFontType by preferencesManager.bubbleAiFontType.collectAsState(
        initial = UserPreferencesManager.FONT_TYPE_SYSTEM,
    )
    val bubbleAiSystemFontName by preferencesManager.bubbleAiSystemFontName.collectAsState(
        initial = UserPreferencesManager.SYSTEM_FONT_DEFAULT,
    )
    val bubbleAiCustomFontPath by preferencesManager.bubbleAiCustomFontPath.collectAsState(initial = null)
    val bubbleUserUseImage by preferencesManager.bubbleUserUseImage.collectAsState(initial = false)
    val bubbleAiUseImage by preferencesManager.bubbleAiUseImage.collectAsState(initial = false)
    val bubbleUserImageUri by preferencesManager.bubbleUserImageUri.collectAsState(initial = null)
    val bubbleAiImageUri by preferencesManager.bubbleAiImageUri.collectAsState(initial = null)
    val bubbleUserImageCropLeft by preferencesManager.bubbleUserImageCropLeft.collectAsState(initial = 0f)
    val bubbleUserImageCropTop by preferencesManager.bubbleUserImageCropTop.collectAsState(initial = 0f)
    val bubbleUserImageCropRight by preferencesManager.bubbleUserImageCropRight.collectAsState(initial = 0f)
    val bubbleUserImageCropBottom by preferencesManager.bubbleUserImageCropBottom.collectAsState(initial = 0f)
    val bubbleUserImageRepeatStart by preferencesManager.bubbleUserImageRepeatStart.collectAsState(initial = 0.35f)
    val bubbleUserImageRepeatEnd by preferencesManager.bubbleUserImageRepeatEnd.collectAsState(initial = 0.65f)
    val bubbleUserImageRepeatYStart by preferencesManager.bubbleUserImageRepeatYStart.collectAsState(initial = 0.35f)
    val bubbleUserImageRepeatYEnd by preferencesManager.bubbleUserImageRepeatYEnd.collectAsState(initial = 0.65f)
    val bubbleUserImageScale by preferencesManager.bubbleUserImageScale.collectAsState(initial = 1f)
    val bubbleAiImageCropLeft by preferencesManager.bubbleAiImageCropLeft.collectAsState(initial = 0f)
    val bubbleAiImageCropTop by preferencesManager.bubbleAiImageCropTop.collectAsState(initial = 0f)
    val bubbleAiImageCropRight by preferencesManager.bubbleAiImageCropRight.collectAsState(initial = 0f)
    val bubbleAiImageCropBottom by preferencesManager.bubbleAiImageCropBottom.collectAsState(initial = 0f)
    val bubbleAiImageRepeatStart by preferencesManager.bubbleAiImageRepeatStart.collectAsState(initial = 0.35f)
    val bubbleAiImageRepeatEnd by preferencesManager.bubbleAiImageRepeatEnd.collectAsState(initial = 0.65f)
    val bubbleAiImageRepeatYStart by preferencesManager.bubbleAiImageRepeatYStart.collectAsState(initial = 0.35f)
    val bubbleAiImageRepeatYEnd by preferencesManager.bubbleAiImageRepeatYEnd.collectAsState(initial = 0.65f)
    val bubbleAiImageScale by preferencesManager.bubbleAiImageScale.collectAsState(initial = 1f)
    val bubbleImageRenderMode by preferencesManager.bubbleImageRenderMode.collectAsState(
        initial = UserPreferencesManager.BUBBLE_IMAGE_RENDER_MODE_TILED_NINE_SLICE,
    )
    val bubbleUserRoundedCornersEnabled by preferencesManager.bubbleUserRoundedCornersEnabled.collectAsState(initial = true)
    val bubbleAiRoundedCornersEnabled by preferencesManager.bubbleAiRoundedCornersEnabled.collectAsState(initial = true)
    val bubbleUserContentPaddingLeft by preferencesManager.bubbleUserContentPaddingLeft.collectAsState(initial = 12f)
    val bubbleUserContentPaddingRight by preferencesManager.bubbleUserContentPaddingRight.collectAsState(initial = 12f)
    val bubbleAiContentPaddingLeft by preferencesManager.bubbleAiContentPaddingLeft.collectAsState(initial = 12f)
    val bubbleAiContentPaddingRight by preferencesManager.bubbleAiContentPaddingRight.collectAsState(initial = 12f)
    val userAvatarUri by preferencesManager.customUserAvatarUri.collectAsState(initial = null)
    val aiAvatarUri by preferencesManager.customAiAvatarUri.collectAsState(initial = null)
    val globalUserAvatarUri by displayPreferencesManager.globalUserAvatarUri.collectAsState(initial = null)
    val globalUserName by displayPreferencesManager.globalUserName.collectAsState(initial = null)
    val avatarShape by preferencesManager.avatarShape.collectAsState(
        initial = UserPreferencesManager.AVATAR_SHAPE_CIRCLE,
    )
    val avatarCornerRadius by preferencesManager.avatarCornerRadius.collectAsState(initial = 8f)
    val showThinkingProcess by preferencesManager.showThinkingProcess.collectAsState(initial = true)
    val showStatusTags by preferencesManager.showStatusTags.collectAsState(initial = true)
    val showModelProvider by preferencesManager.showModelProvider.collectAsState(initial = false)
    val showModelName by preferencesManager.showModelName.collectAsState(initial = false)
    val showRoleName by preferencesManager.showRoleName.collectAsState(initial = true)
    val showUserName by preferencesManager.showUserName.collectAsState(initial = true)
    val showMessageTokenStats by preferencesManager.showMessageTokenStats.collectAsState(initial = false)
    val showMessageTimingStats by preferencesManager.showMessageTimingStats.collectAsState(initial = false)
    val showMessageTimestamp by preferencesManager.showMessageTimestamp.collectAsState(initial = false)
    val showInputProcessingStatus by preferencesManager.showInputProcessingStatus.collectAsState(initial = true)
    val showChatFloatingDotsAnimation by preferencesManager.showChatFloatingDotsAnimation.collectAsState(initial = true)
    val recentColors by preferencesManager.recentColorsFlow.collectAsState(initial = emptyList())
    val userBubbleColor = bubbleUserBubbleColor ?: defaultBubbleUserBubbleColor
    val aiBubbleColor = bubbleAiBubbleColor ?: defaultBubbleAiBubbleColor
    val effectiveBubbleUserTextColor =
        bubbleUserTextColor ?: getTextColorForBackground(Color(userBubbleColor)).toArgb()
    val effectiveBubbleAiTextColor =
        bubbleAiTextColor ?: getTextColorForBackground(Color(aiBubbleColor)).toArgb()
    var showColorPicker by remember { mutableStateOf(false) }
    var currentColorPickerMode by remember { mutableStateOf("bubbleUserBubble") }

    var chatStyleInput by remember { mutableStateOf(chatStyle) }
    var inputStyleInput by remember { mutableStateOf(inputStyle) }
    var bubbleShowAvatarInput by remember { mutableStateOf(bubbleShowAvatar) }
    var bubbleWideLayoutEnabledInput by remember { mutableStateOf(bubbleWideLayoutEnabled) }
    var cursorUserBubbleFollowThemeInput by remember { mutableStateOf(cursorUserBubbleFollowTheme) }
    var cursorUserBubbleLiquidGlassInput by remember { mutableStateOf(cursorUserBubbleLiquidGlass) }
    var cursorUserBubbleWaterGlassInput by remember { mutableStateOf(cursorUserBubbleWaterGlass) }
    var bubbleUserBubbleLiquidGlassInput by remember { mutableStateOf(bubbleUserBubbleLiquidGlass) }
    var bubbleUserBubbleWaterGlassInput by remember { mutableStateOf(bubbleUserBubbleWaterGlass) }
    var bubbleAiBubbleLiquidGlassInput by remember { mutableStateOf(bubbleAiBubbleLiquidGlass) }
    var bubbleAiBubbleWaterGlassInput by remember { mutableStateOf(bubbleAiBubbleWaterGlass) }
    var cursorUserBubbleColorInput by remember { mutableStateOf(cursorUserBubbleColor ?: defaultCursorUserBubbleColor) }
    var bubbleUserBubbleColorInput by remember { mutableStateOf(userBubbleColor) }
    var bubbleAiBubbleColorInput by remember { mutableStateOf(aiBubbleColor) }
    var bubbleUserTextColorInput by remember { mutableStateOf(effectiveBubbleUserTextColor) }
    var bubbleAiTextColorInput by remember { mutableStateOf(effectiveBubbleAiTextColor) }
    var bubbleUserUseCustomFontInput by remember { mutableStateOf(bubbleUserUseCustomFont) }
    var bubbleUserFontTypeInput by remember { mutableStateOf(bubbleUserFontType) }
    var bubbleUserSystemFontNameInput by remember { mutableStateOf(bubbleUserSystemFontName) }
    var bubbleUserCustomFontPathInput by remember { mutableStateOf(bubbleUserCustomFontPath) }
    var bubbleAiUseCustomFontInput by remember { mutableStateOf(bubbleAiUseCustomFont) }
    var bubbleAiFontTypeInput by remember { mutableStateOf(bubbleAiFontType) }
    var bubbleAiSystemFontNameInput by remember { mutableStateOf(bubbleAiSystemFontName) }
    var bubbleAiCustomFontPathInput by remember { mutableStateOf(bubbleAiCustomFontPath) }
    var bubbleUserUseImageInput by remember { mutableStateOf(bubbleUserUseImage) }
    var bubbleAiUseImageInput by remember { mutableStateOf(bubbleAiUseImage) }
    var bubbleUserImageUriInput by remember { mutableStateOf(bubbleUserImageUri) }
    var bubbleAiImageUriInput by remember { mutableStateOf(bubbleAiImageUri) }
    var bubbleUserImageCropLeftInput by remember { mutableStateOf(bubbleUserImageCropLeft) }
    var bubbleUserImageCropTopInput by remember { mutableStateOf(bubbleUserImageCropTop) }
    var bubbleUserImageCropRightInput by remember { mutableStateOf(bubbleUserImageCropRight) }
    var bubbleUserImageCropBottomInput by remember { mutableStateOf(bubbleUserImageCropBottom) }
    var bubbleUserImageRepeatStartInput by remember { mutableStateOf(bubbleUserImageRepeatStart) }
    var bubbleUserImageRepeatEndInput by remember { mutableStateOf(bubbleUserImageRepeatEnd) }
    var bubbleUserImageRepeatYStartInput by remember { mutableStateOf(bubbleUserImageRepeatYStart) }
    var bubbleUserImageRepeatYEndInput by remember { mutableStateOf(bubbleUserImageRepeatYEnd) }
    var bubbleUserImageScaleInput by remember { mutableStateOf(bubbleUserImageScale) }
    var bubbleAiImageCropLeftInput by remember { mutableStateOf(bubbleAiImageCropLeft) }
    var bubbleAiImageCropTopInput by remember { mutableStateOf(bubbleAiImageCropTop) }
    var bubbleAiImageCropRightInput by remember { mutableStateOf(bubbleAiImageCropRight) }
    var bubbleAiImageCropBottomInput by remember { mutableStateOf(bubbleAiImageCropBottom) }
    var bubbleAiImageRepeatStartInput by remember { mutableStateOf(bubbleAiImageRepeatStart) }
    var bubbleAiImageRepeatEndInput by remember { mutableStateOf(bubbleAiImageRepeatEnd) }
    var bubbleAiImageRepeatYStartInput by remember { mutableStateOf(bubbleAiImageRepeatYStart) }
    var bubbleAiImageRepeatYEndInput by remember { mutableStateOf(bubbleAiImageRepeatYEnd) }
    var bubbleAiImageScaleInput by remember { mutableStateOf(bubbleAiImageScale) }
    var bubbleImageRenderModeInput by remember { mutableStateOf(bubbleImageRenderMode) }
    var bubbleUserRoundedCornersEnabledInput by remember { mutableStateOf(bubbleUserRoundedCornersEnabled) }
    var bubbleAiRoundedCornersEnabledInput by remember { mutableStateOf(bubbleAiRoundedCornersEnabled) }
    var bubbleUserContentPaddingLeftInput by remember { mutableStateOf(bubbleUserContentPaddingLeft) }
    var bubbleUserContentPaddingRightInput by remember { mutableStateOf(bubbleUserContentPaddingRight) }
    var bubbleAiContentPaddingLeftInput by remember { mutableStateOf(bubbleAiContentPaddingLeft) }
    var bubbleAiContentPaddingRightInput by remember { mutableStateOf(bubbleAiContentPaddingRight) }
    var userAvatarUriInput by remember { mutableStateOf(userAvatarUri) }
    var aiAvatarUriInput by remember { mutableStateOf(aiAvatarUri) }
    var globalUserAvatarUriInput by remember { mutableStateOf(globalUserAvatarUri) }
    var globalUserNameInput by remember { mutableStateOf(globalUserName) }
    var avatarShapeInput by remember { mutableStateOf(avatarShape) }
    var avatarCornerRadiusInput by remember { mutableStateOf(avatarCornerRadius) }
    var showThinkingProcessInput by remember { mutableStateOf(showThinkingProcess) }
    var showStatusTagsInput by remember { mutableStateOf(showStatusTags) }
    var showModelProviderInput by remember { mutableStateOf(showModelProvider) }
    var showModelNameInput by remember { mutableStateOf(showModelName) }
    var showRoleNameInput by remember { mutableStateOf(showRoleName) }
    var showUserNameInput by remember { mutableStateOf(showUserName) }
    var showMessageTokenStatsInput by remember { mutableStateOf(showMessageTokenStats) }
    var showMessageTimingStatsInput by remember { mutableStateOf(showMessageTimingStats) }
    var showMessageTimestampInput by remember { mutableStateOf(showMessageTimestamp) }
    var showInputProcessingStatusInput by remember { mutableStateOf(showInputProcessingStatus) }
    var showChatFloatingDotsAnimationInput by remember { mutableStateOf(showChatFloatingDotsAnimation) }

    LaunchedEffect(
        chatStyle,
        inputStyle,
        bubbleShowAvatar,
        bubbleWideLayoutEnabled,
        cursorUserBubbleFollowTheme,
        cursorUserBubbleLiquidGlass,
        cursorUserBubbleWaterGlass,
        bubbleUserBubbleLiquidGlass,
        bubbleUserBubbleWaterGlass,
        bubbleAiBubbleLiquidGlass,
        bubbleAiBubbleWaterGlass,
        cursorUserBubbleColor,
        userBubbleColor,
        aiBubbleColor,
        effectiveBubbleUserTextColor,
        effectiveBubbleAiTextColor,
        bubbleUserUseCustomFont,
        bubbleUserFontType,
        bubbleUserSystemFontName,
        bubbleUserCustomFontPath,
        bubbleAiUseCustomFont,
        bubbleAiFontType,
        bubbleAiSystemFontName,
        bubbleAiCustomFontPath,
        bubbleUserUseImage,
        bubbleAiUseImage,
        bubbleUserImageUri,
        bubbleAiImageUri,
        bubbleUserImageCropLeft,
        bubbleUserImageCropTop,
        bubbleUserImageCropRight,
        bubbleUserImageCropBottom,
        bubbleUserImageRepeatStart,
        bubbleUserImageRepeatEnd,
        bubbleUserImageRepeatYStart,
        bubbleUserImageRepeatYEnd,
        bubbleUserImageScale,
        bubbleAiImageCropLeft,
        bubbleAiImageCropTop,
        bubbleAiImageCropRight,
        bubbleAiImageCropBottom,
        bubbleAiImageRepeatStart,
        bubbleAiImageRepeatEnd,
        bubbleAiImageRepeatYStart,
        bubbleAiImageRepeatYEnd,
        bubbleAiImageScale,
        bubbleImageRenderMode,
        bubbleUserRoundedCornersEnabled,
        bubbleAiRoundedCornersEnabled,
        bubbleUserContentPaddingLeft,
        bubbleUserContentPaddingRight,
        bubbleAiContentPaddingLeft,
        bubbleAiContentPaddingRight,
        userAvatarUri,
        aiAvatarUri,
        globalUserAvatarUri,
        globalUserName,
        avatarShape,
        avatarCornerRadius,
        showThinkingProcess,
        showStatusTags,
        showModelProvider,
        showModelName,
        showRoleName,
        showUserName,
        showMessageTokenStats,
        showMessageTimingStats,
        showMessageTimestamp,
        showInputProcessingStatus,
        showChatFloatingDotsAnimation,
    ) {
        chatStyleInput = chatStyle
        inputStyleInput = inputStyle
        bubbleShowAvatarInput = bubbleShowAvatar
        bubbleWideLayoutEnabledInput = bubbleWideLayoutEnabled
        cursorUserBubbleFollowThemeInput = cursorUserBubbleFollowTheme
        cursorUserBubbleLiquidGlassInput = cursorUserBubbleLiquidGlass
        cursorUserBubbleWaterGlassInput = cursorUserBubbleWaterGlass
        bubbleUserBubbleLiquidGlassInput = bubbleUserBubbleLiquidGlass
        bubbleUserBubbleWaterGlassInput = bubbleUserBubbleWaterGlass
        bubbleAiBubbleLiquidGlassInput = bubbleAiBubbleLiquidGlass
        bubbleAiBubbleWaterGlassInput = bubbleAiBubbleWaterGlass
        cursorUserBubbleColorInput = cursorUserBubbleColor ?: defaultCursorUserBubbleColor
        bubbleUserBubbleColorInput = userBubbleColor
        bubbleAiBubbleColorInput = aiBubbleColor
        bubbleUserTextColorInput = effectiveBubbleUserTextColor
        bubbleAiTextColorInput = effectiveBubbleAiTextColor
        bubbleUserUseCustomFontInput = bubbleUserUseCustomFont
        bubbleUserFontTypeInput = bubbleUserFontType
        bubbleUserSystemFontNameInput = bubbleUserSystemFontName
        bubbleUserCustomFontPathInput = bubbleUserCustomFontPath
        bubbleAiUseCustomFontInput = bubbleAiUseCustomFont
        bubbleAiFontTypeInput = bubbleAiFontType
        bubbleAiSystemFontNameInput = bubbleAiSystemFontName
        bubbleAiCustomFontPathInput = bubbleAiCustomFontPath
        bubbleUserUseImageInput = bubbleUserUseImage
        bubbleAiUseImageInput = bubbleAiUseImage
        bubbleUserImageUriInput = bubbleUserImageUri
        bubbleAiImageUriInput = bubbleAiImageUri
        bubbleUserImageCropLeftInput = bubbleUserImageCropLeft
        bubbleUserImageCropTopInput = bubbleUserImageCropTop
        bubbleUserImageCropRightInput = bubbleUserImageCropRight
        bubbleUserImageCropBottomInput = bubbleUserImageCropBottom
        bubbleUserImageRepeatStartInput = bubbleUserImageRepeatStart
        bubbleUserImageRepeatEndInput = bubbleUserImageRepeatEnd
        bubbleUserImageRepeatYStartInput = bubbleUserImageRepeatYStart
        bubbleUserImageRepeatYEndInput = bubbleUserImageRepeatYEnd
        bubbleUserImageScaleInput = bubbleUserImageScale
        bubbleAiImageCropLeftInput = bubbleAiImageCropLeft
        bubbleAiImageCropTopInput = bubbleAiImageCropTop
        bubbleAiImageCropRightInput = bubbleAiImageCropRight
        bubbleAiImageCropBottomInput = bubbleAiImageCropBottom
        bubbleAiImageRepeatStartInput = bubbleAiImageRepeatStart
        bubbleAiImageRepeatEndInput = bubbleAiImageRepeatEnd
        bubbleAiImageRepeatYStartInput = bubbleAiImageRepeatYStart
        bubbleAiImageRepeatYEndInput = bubbleAiImageRepeatYEnd
        bubbleAiImageScaleInput = bubbleAiImageScale
        bubbleImageRenderModeInput = bubbleImageRenderMode
        bubbleUserRoundedCornersEnabledInput = bubbleUserRoundedCornersEnabled
        bubbleAiRoundedCornersEnabledInput = bubbleAiRoundedCornersEnabled
        bubbleUserContentPaddingLeftInput = bubbleUserContentPaddingLeft
        bubbleUserContentPaddingRightInput = bubbleUserContentPaddingRight
        bubbleAiContentPaddingLeftInput = bubbleAiContentPaddingLeft
        bubbleAiContentPaddingRightInput = bubbleAiContentPaddingRight
        userAvatarUriInput = userAvatarUri
        aiAvatarUriInput = aiAvatarUri
        globalUserAvatarUriInput = globalUserAvatarUri
        globalUserNameInput = globalUserName
        avatarShapeInput = avatarShape
        avatarCornerRadiusInput = avatarCornerRadius
        showThinkingProcessInput = showThinkingProcess
        showStatusTagsInput = showStatusTags
        showModelProviderInput = showModelProvider
        showModelNameInput = showModelName
        showRoleNameInput = showRoleName
        showUserNameInput = showUserName
        showMessageTokenStatsInput = showMessageTokenStats
        showMessageTimingStatsInput = showMessageTimingStats
        showMessageTimestampInput = showMessageTimestamp
        showInputProcessingStatusInput = showInputProcessingStatus
        showChatFloatingDotsAnimationInput = showChatFloatingDotsAnimation
    }

    val bubbleFontPicker = rememberBubbleFontPicker(shared = shared)
    val runtime = rememberThemeSettingsChatRuntime(
        state = ThemeSettingsChatRuntimeState(
            context = shared.context,
            scope = shared.scope,
            preferencesManager = preferencesManager,
            displayPreferencesManager = displayPreferencesManager,
            saveThemeSettingsWithCharacterCard = shared.saveThemeSettingsWithCharacterCard,
            bubbleUserBubbleLiquidGlassInput = bubbleUserBubbleLiquidGlassInput,
            bubbleUserBubbleWaterGlassInput = bubbleUserBubbleWaterGlassInput,
            bubbleAiBubbleLiquidGlassInput = bubbleAiBubbleLiquidGlassInput,
            bubbleAiBubbleWaterGlassInput = bubbleAiBubbleWaterGlassInput,
            onBubbleUserUseImageInputChange = { bubbleUserUseImageInput = it },
            onBubbleAiUseImageInputChange = { bubbleAiUseImageInput = it },
            onBubbleUserImageUriInputChange = { bubbleUserImageUriInput = it },
            onBubbleAiImageUriInputChange = { bubbleAiImageUriInput = it },
            onBubbleUserImageCropLeftInputChange = { bubbleUserImageCropLeftInput = it },
            onBubbleUserImageCropTopInputChange = { bubbleUserImageCropTopInput = it },
            onBubbleUserImageCropRightInputChange = { bubbleUserImageCropRightInput = it },
            onBubbleUserImageCropBottomInputChange = { bubbleUserImageCropBottomInput = it },
            onBubbleUserImageRepeatStartInputChange = { bubbleUserImageRepeatStartInput = it },
            onBubbleUserImageRepeatEndInputChange = { bubbleUserImageRepeatEndInput = it },
            onBubbleUserImageRepeatYStartInputChange = { bubbleUserImageRepeatYStartInput = it },
            onBubbleUserImageRepeatYEndInputChange = { bubbleUserImageRepeatYEndInput = it },
            onBubbleUserImageScaleInputChange = { bubbleUserImageScaleInput = it },
            onBubbleAiImageCropLeftInputChange = { bubbleAiImageCropLeftInput = it },
            onBubbleAiImageCropTopInputChange = { bubbleAiImageCropTopInput = it },
            onBubbleAiImageCropRightInputChange = { bubbleAiImageCropRightInput = it },
            onBubbleAiImageCropBottomInputChange = { bubbleAiImageCropBottomInput = it },
            onBubbleAiImageRepeatStartInputChange = { bubbleAiImageRepeatStartInput = it },
            onBubbleAiImageRepeatEndInputChange = { bubbleAiImageRepeatEndInput = it },
            onBubbleAiImageRepeatYStartInputChange = { bubbleAiImageRepeatYStartInput = it },
            onBubbleAiImageRepeatYEndInputChange = { bubbleAiImageRepeatYEndInput = it },
            onBubbleAiImageScaleInputChange = { bubbleAiImageScaleInput = it },
            onBubbleImageRenderModeInputChange = { bubbleImageRenderModeInput = it },
            onUserAvatarUriInputChange = { userAvatarUriInput = it },
            onAiAvatarUriInputChange = { aiAvatarUriInput = it },
            onGlobalUserAvatarUriInputChange = { globalUserAvatarUriInput = it },
        ),
    )

    ThemeSettingsChatStyleSection(
        cardColors = cardColors,
        chatStyleInput = chatStyleInput,
        onChatStyleInputChange = { chatStyleInput = it },
        inputStyleInput = inputStyleInput,
        onInputStyleInputChange = { inputStyleInput = it },
        bubbleShowAvatarInput = bubbleShowAvatarInput,
        onBubbleShowAvatarInputChange = { bubbleShowAvatarInput = it },
        bubbleWideLayoutEnabledInput = bubbleWideLayoutEnabledInput,
        onBubbleWideLayoutEnabledInputChange = { bubbleWideLayoutEnabledInput = it },
        cursorUserBubbleFollowThemeInput = cursorUserBubbleFollowThemeInput,
        onCursorUserBubbleFollowThemeInputChange = { cursorUserBubbleFollowThemeInput = it },
        cursorUserBubbleLiquidGlassInput = cursorUserBubbleLiquidGlassInput,
        onCursorUserBubbleLiquidGlassInputChange = { cursorUserBubbleLiquidGlassInput = it },
        cursorUserBubbleWaterGlassInput = cursorUserBubbleWaterGlassInput,
        onCursorUserBubbleWaterGlassInputChange = { cursorUserBubbleWaterGlassInput = it },
        bubbleUserBubbleLiquidGlassInput = bubbleUserBubbleLiquidGlassInput,
        onBubbleUserBubbleLiquidGlassInputChange = { bubbleUserBubbleLiquidGlassInput = it },
        bubbleUserBubbleWaterGlassInput = bubbleUserBubbleWaterGlassInput,
        onBubbleUserBubbleWaterGlassInputChange = { bubbleUserBubbleWaterGlassInput = it },
        bubbleAiBubbleLiquidGlassInput = bubbleAiBubbleLiquidGlassInput,
        onBubbleAiBubbleLiquidGlassInputChange = { bubbleAiBubbleLiquidGlassInput = it },
        bubbleAiBubbleWaterGlassInput = bubbleAiBubbleWaterGlassInput,
        onBubbleAiBubbleWaterGlassInputChange = { bubbleAiBubbleWaterGlassInput = it },
        cursorUserBubbleColorInput = cursorUserBubbleColorInput,
        bubbleUserBubbleColorInput = bubbleUserBubbleColorInput,
        bubbleAiBubbleColorInput = bubbleAiBubbleColorInput,
        bubbleUserTextColorInput = bubbleUserTextColorInput,
        bubbleAiTextColorInput = bubbleAiTextColorInput,
        bubbleUserUseCustomFontInput = bubbleUserUseCustomFontInput,
        onBubbleUserUseCustomFontInputChange = { bubbleUserUseCustomFontInput = it },
        bubbleUserFontTypeInput = bubbleUserFontTypeInput,
        onBubbleUserFontTypeInputChange = { bubbleUserFontTypeInput = it },
        bubbleUserSystemFontNameInput = bubbleUserSystemFontNameInput,
        onBubbleUserSystemFontNameInputChange = { bubbleUserSystemFontNameInput = it },
        bubbleUserCustomFontPathInput = bubbleUserCustomFontPathInput,
        onBubbleUserCustomFontPathInputChange = { bubbleUserCustomFontPathInput = it },
        onPickBubbleUserFont = bubbleFontPicker.onPickBubbleUserFont,
        bubbleAiUseCustomFontInput = bubbleAiUseCustomFontInput,
        onBubbleAiUseCustomFontInputChange = { bubbleAiUseCustomFontInput = it },
        bubbleAiFontTypeInput = bubbleAiFontTypeInput,
        onBubbleAiFontTypeInputChange = { bubbleAiFontTypeInput = it },
        bubbleAiSystemFontNameInput = bubbleAiSystemFontNameInput,
        onBubbleAiSystemFontNameInputChange = { bubbleAiSystemFontNameInput = it },
        bubbleAiCustomFontPathInput = bubbleAiCustomFontPathInput,
        onBubbleAiCustomFontPathInputChange = { bubbleAiCustomFontPathInput = it },
        onPickBubbleAiFont = bubbleFontPicker.onPickBubbleAiFont,
        previewUserAvatarUri = userAvatarUriInput ?: globalUserAvatarUriInput,
        previewAiAvatarUri = aiAvatarUriInput ?: shared.activeThemeTargetAvatarUri,
        onShowColorPicker = {
            currentColorPickerMode = it
            showColorPicker = true
        },
        bubbleUserUseImageInput = bubbleUserUseImageInput,
        onBubbleUserUseImageInputChange = { bubbleUserUseImageInput = it },
        bubbleAiUseImageInput = bubbleAiUseImageInput,
        onBubbleAiUseImageInputChange = { bubbleAiUseImageInput = it },
        bubbleUserImageUriInput = bubbleUserImageUriInput,
        bubbleAiImageUriInput = bubbleAiImageUriInput,
        onPickBubbleUserImage = runtime.onPickBubbleUserImage,
        onPickBubbleAiImage = runtime.onPickBubbleAiImage,
        onClearBubbleUserImage = runtime.onClearBubbleUserImage,
        onClearBubbleAiImage = runtime.onClearBubbleAiImage,
        bubbleUserImageCropLeftInput = bubbleUserImageCropLeftInput,
        onBubbleUserImageCropLeftInputChange = { bubbleUserImageCropLeftInput = it },
        bubbleUserImageCropTopInput = bubbleUserImageCropTopInput,
        onBubbleUserImageCropTopInputChange = { bubbleUserImageCropTopInput = it },
        bubbleUserImageCropRightInput = bubbleUserImageCropRightInput,
        onBubbleUserImageCropRightInputChange = { bubbleUserImageCropRightInput = it },
        bubbleUserImageCropBottomInput = bubbleUserImageCropBottomInput,
        onBubbleUserImageCropBottomInputChange = { bubbleUserImageCropBottomInput = it },
        bubbleUserImageRepeatStartInput = bubbleUserImageRepeatStartInput,
        onBubbleUserImageRepeatStartInputChange = { bubbleUserImageRepeatStartInput = it },
        bubbleUserImageRepeatEndInput = bubbleUserImageRepeatEndInput,
        onBubbleUserImageRepeatEndInputChange = { bubbleUserImageRepeatEndInput = it },
        bubbleUserImageRepeatYStartInput = bubbleUserImageRepeatYStartInput,
        onBubbleUserImageRepeatYStartInputChange = { bubbleUserImageRepeatYStartInput = it },
        bubbleUserImageRepeatYEndInput = bubbleUserImageRepeatYEndInput,
        onBubbleUserImageRepeatYEndInputChange = { bubbleUserImageRepeatYEndInput = it },
        bubbleUserImageScaleInput = bubbleUserImageScaleInput,
        onBubbleUserImageScaleInputChange = { bubbleUserImageScaleInput = it },
        bubbleAiImageCropLeftInput = bubbleAiImageCropLeftInput,
        onBubbleAiImageCropLeftInputChange = { bubbleAiImageCropLeftInput = it },
        bubbleAiImageCropTopInput = bubbleAiImageCropTopInput,
        onBubbleAiImageCropTopInputChange = { bubbleAiImageCropTopInput = it },
        bubbleAiImageCropRightInput = bubbleAiImageCropRightInput,
        onBubbleAiImageCropRightInputChange = { bubbleAiImageCropRightInput = it },
        bubbleAiImageCropBottomInput = bubbleAiImageCropBottomInput,
        onBubbleAiImageCropBottomInputChange = { bubbleAiImageCropBottomInput = it },
        bubbleAiImageRepeatStartInput = bubbleAiImageRepeatStartInput,
        onBubbleAiImageRepeatStartInputChange = { bubbleAiImageRepeatStartInput = it },
        bubbleAiImageRepeatEndInput = bubbleAiImageRepeatEndInput,
        onBubbleAiImageRepeatEndInputChange = { bubbleAiImageRepeatEndInput = it },
        bubbleAiImageRepeatYStartInput = bubbleAiImageRepeatYStartInput,
        onBubbleAiImageRepeatYStartInputChange = { bubbleAiImageRepeatYStartInput = it },
        bubbleAiImageRepeatYEndInput = bubbleAiImageRepeatYEndInput,
        onBubbleAiImageRepeatYEndInputChange = { bubbleAiImageRepeatYEndInput = it },
        bubbleAiImageScaleInput = bubbleAiImageScaleInput,
        onBubbleAiImageScaleInputChange = { bubbleAiImageScaleInput = it },
        bubbleImageRenderModeInput = bubbleImageRenderModeInput,
        onBubbleImageRenderModeInputChange = { bubbleImageRenderModeInput = it },
        bubbleUserRoundedCornersEnabledInput = bubbleUserRoundedCornersEnabledInput,
        onBubbleUserRoundedCornersEnabledInputChange = { bubbleUserRoundedCornersEnabledInput = it },
        bubbleAiRoundedCornersEnabledInput = bubbleAiRoundedCornersEnabledInput,
        onBubbleAiRoundedCornersEnabledInputChange = { bubbleAiRoundedCornersEnabledInput = it },
        bubbleUserContentPaddingLeftInput = bubbleUserContentPaddingLeftInput,
        onBubbleUserContentPaddingLeftInputChange = { bubbleUserContentPaddingLeftInput = it },
        bubbleUserContentPaddingRightInput = bubbleUserContentPaddingRightInput,
        onBubbleUserContentPaddingRightInputChange = { bubbleUserContentPaddingRightInput = it },
        bubbleAiContentPaddingLeftInput = bubbleAiContentPaddingLeftInput,
        onBubbleAiContentPaddingLeftInputChange = { bubbleAiContentPaddingLeftInput = it },
        bubbleAiContentPaddingRightInput = bubbleAiContentPaddingRightInput,
        onBubbleAiContentPaddingRightInputChange = { bubbleAiContentPaddingRightInput = it },
        saveThemeSettingsWithCharacterCard = shared.saveThemeSettingsWithCharacterCard,
        preferencesManager = preferencesManager,
        showInputStyleControls = false,
    )

    ThemeSettingsAvatarSection(
        cardColors = cardColors,
        scope = shared.scope,
        preferencesManager = preferencesManager,
        displayPreferencesManager = displayPreferencesManager,
        saveThemeSettingsWithCharacterCard = shared.saveThemeSettingsWithCharacterCard,
        userAvatarUriInput = userAvatarUriInput,
        onUserAvatarUriInputChange = { userAvatarUriInput = it },
        globalUserAvatarUriInput = globalUserAvatarUriInput,
        onGlobalUserAvatarUriInputChange = { globalUserAvatarUriInput = it },
        globalUserNameInput = globalUserNameInput,
        onGlobalUserNameInputChange = { globalUserNameInput = it },
        avatarShapeInput = avatarShapeInput,
        onAvatarShapeInputChange = { avatarShapeInput = it },
        avatarCornerRadiusInput = avatarCornerRadiusInput,
        onAvatarCornerRadiusInputChange = { avatarCornerRadiusInput = it },
        avatarImagePicker = runtime.avatarImagePicker,
        onAvatarPickerModeChange = runtime.onAvatarPickerModeChange,
    )

    ThemeSettingsDisplayOptionsSection(
        cardColors = cardColors,
        showThinkingProcessInput = showThinkingProcessInput,
        onShowThinkingProcessInputChange = { showThinkingProcessInput = it },
        showStatusTagsInput = showStatusTagsInput,
        onShowStatusTagsInputChange = { showStatusTagsInput = it },
        showModelProviderInput = showModelProviderInput,
        onShowModelProviderInputChange = { showModelProviderInput = it },
        showModelNameInput = showModelNameInput,
        onShowModelNameInputChange = { showModelNameInput = it },
        showRoleNameInput = showRoleNameInput,
        onShowRoleNameInputChange = { showRoleNameInput = it },
        showUserNameInput = showUserNameInput,
        onShowUserNameInputChange = { showUserNameInput = it },
        showMessageTokenStatsInput = showMessageTokenStatsInput,
        onShowMessageTokenStatsInputChange = { showMessageTokenStatsInput = it },
        showMessageTimingStatsInput = showMessageTimingStatsInput,
        onShowMessageTimingStatsInputChange = { showMessageTimingStatsInput = it },
        showMessageTimestampInput = showMessageTimestampInput,
        onShowMessageTimestampInputChange = { showMessageTimestampInput = it },
        showInputProcessingStatusInput = showInputProcessingStatusInput,
        onShowInputProcessingStatusInputChange = { showInputProcessingStatusInput = it },
        showChatFloatingDotsAnimationInput = showChatFloatingDotsAnimationInput,
        onShowChatFloatingDotsAnimationInputChange = { showChatFloatingDotsAnimationInput = it },
        saveThemeSettingsWithCharacterCard = shared.saveThemeSettingsWithCharacterCard,
        preferencesManager = preferencesManager,
    )

    if (showColorPicker) {
        ColorPickerDialog(
            showColorPicker = showColorPicker,
            currentColorPickerMode = currentColorPickerMode,
            primaryColorInput = MaterialTheme.colorScheme.primary.toArgb(),
            secondaryColorInput = MaterialTheme.colorScheme.secondary.toArgb(),
            statusBarColorInput = MaterialTheme.colorScheme.surface.toArgb(),
            appBarColorInput = MaterialTheme.colorScheme.surface.toArgb(),
            navigationDrawerBackgroundColorInput = MaterialTheme.colorScheme.surface.toArgb(),
            navigationDrawerAccentColorInput = MaterialTheme.colorScheme.primary.toArgb(),
            historyIconColorInput = Color.Gray.toArgb(),
            pipIconColorInput = Color.Gray.toArgb(),
            cursorUserBubbleColorInput = cursorUserBubbleColorInput,
            bubbleUserBubbleColorInput = bubbleUserBubbleColorInput,
            bubbleAiBubbleColorInput = bubbleAiBubbleColorInput,
            bubbleUserTextColorInput = bubbleUserTextColorInput,
            bubbleAiTextColorInput = bubbleAiTextColorInput,
            recentColors = recentColors,
            onColorSelected = { _, _, _, _, _, _, _, _, cursorUser, bubbleUser, bubbleAi, userText, aiText ->
                saveSelectedChatColor(
                    shared = shared,
                    currentColorPickerMode = currentColorPickerMode,
                    cursorUserBubbleColor = cursorUser,
                    bubbleUserBubbleColor = bubbleUser,
                    bubbleAiBubbleColor = bubbleAi,
                    bubbleUserTextColor = userText,
                    bubbleAiTextColor = aiText,
                )
            },
            onDismiss = { showColorPicker = false },
        )
    }
}

private data class BubbleFontPicker(
    val onPickBubbleUserFont: () -> Unit,
    val onPickBubbleAiFont: () -> Unit,
)

@Composable
private fun rememberBubbleFontPicker(shared: ThemeSettingsShared): BubbleFontPicker {
    val context = shared.context
    var targetName by remember { mutableStateOf("bubble_user_font") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            shared.scope.launch {
                val extension = FileUtils.getFileExtension(context, uri)?.lowercase()
                if (extension != null && (extension == "ttf" || extension == "otf" || extension == "ttc")) {
                    val internalUri = FileUtils.copyFileToInternalStorage(context, uri, targetName)
                    if (internalUri != null) {
                        val isUser = targetName == "bubble_user_font"
                        shared.saveThemeSettingsWithCharacterCard {
                            if (isUser) {
                                shared.preferencesManager.saveThemeSettings(
                                    bubbleUserCustomFontPath = internalUri.toString(),
                                    bubbleUserFontType = UserPreferencesManager.FONT_TYPE_FILE,
                                )
                            } else {
                                shared.preferencesManager.saveThemeSettings(
                                    bubbleAiCustomFontPath = internalUri.toString(),
                                    bubbleAiFontType = UserPreferencesManager.FONT_TYPE_FILE,
                                )
                            }
                        }
                        Toast.makeText(
                            context,
                            context.getString(R.string.font_file_saved, extension),
                            Toast.LENGTH_SHORT,
                        ).show()
                    } else {
                        Toast.makeText(
                            context,
                            context.getString(R.string.font_file_save_failed),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        context,
                        context.getString(R.string.unsupported_font_format),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
    return BubbleFontPicker(
        onPickBubbleUserFont = {
            targetName = "bubble_user_font"
            launcher.launch("*/*")
        },
        onPickBubbleAiFont = {
            targetName = "bubble_ai_font"
            launcher.launch("*/*")
        },
    )
}

private fun saveSelectedChatColor(
    shared: ThemeSettingsShared,
    currentColorPickerMode: String,
    cursorUserBubbleColor: Int?,
    bubbleUserBubbleColor: Int?,
    bubbleAiBubbleColor: Int?,
    bubbleUserTextColor: Int?,
    bubbleAiTextColor: Int?,
) {
    val selectedColor = cursorUserBubbleColor ?: bubbleUserBubbleColor ?: bubbleAiBubbleColor
        ?: bubbleUserTextColor ?: bubbleAiTextColor
    selectedColor?.let { shared.scope.launch { shared.preferencesManager.addRecentColor(it) } }
    shared.saveThemeSettingsWithCharacterCard {
        when (currentColorPickerMode) {
            "cursorUserBubble" -> cursorUserBubbleColor?.let {
                shared.preferencesManager.saveThemeSettings(cursorUserBubbleColor = it)
            }
            "bubbleUserBubble" -> bubbleUserBubbleColor?.let {
                shared.preferencesManager.saveThemeSettings(bubbleUserBubbleColor = it)
            }
            "bubbleAiBubble" -> bubbleAiBubbleColor?.let {
                shared.preferencesManager.saveThemeSettings(bubbleAiBubbleColor = it)
            }
            "bubbleUserText" -> bubbleUserTextColor?.let {
                shared.preferencesManager.saveThemeSettings(bubbleUserTextColor = it)
            }
            "bubbleAiText" -> bubbleAiTextColor?.let {
                shared.preferencesManager.saveThemeSettings(bubbleAiTextColor = it)
            }
        }
    }
}
