package com.ai.assistance.operit.ui.theme

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.util.AppLogger

/** Static-image-only background layer used by the lean host. */
@Composable
fun AppBackgroundLayer(
    darkTheme: Boolean,
    useBackgroundImage: Boolean,
    backgroundImageUri: String?,
    backgroundImageOpacity: Float,
    backgroundMediaType: String,
    videoBackgroundMuted: Boolean,
    videoBackgroundLoop: Boolean,
    useBackgroundBlur: Boolean,
    backgroundBlurRadius: Float,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val baseBackgroundColor = if (darkTheme) Color.Black else Color.White

    Box(modifier = modifier.background(baseBackgroundColor)) {
        if (
            useBackgroundImage &&
                backgroundImageUri != null &&
                backgroundMediaType != UserPreferencesManager.MEDIA_TYPE_VIDEO
        ) {
            val painter = rememberAsyncImagePainter(model = Uri.parse(backgroundImageUri))
            LaunchedEffect(painter.state) {
                if (painter.state is AsyncImagePainter.State.Error) {
                    AppLogger.e(
                        "AppBackgroundLayer",
                        "Error loading background image from URI: $backgroundImageUri",
                    )
                }
            }
            Image(
                painter = painter,
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(backgroundImageOpacity)
                        .then(
                            if (useBackgroundBlur) {
                                Modifier.blur(backgroundBlurRadius.dp)
                            } else {
                                Modifier
                            },
                        ),
                contentScale = ContentScale.Crop,
            )
        }
    }
}
