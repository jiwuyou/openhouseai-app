package com.ai.assistance.operit.ui.features.settings.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.ai.assistance.operit.R
import com.ai.assistance.operit.data.preferences.UserPreferencesManager
import com.ai.assistance.operit.util.FileUtils
import kotlinx.coroutines.launch

private val leanThemeColors =
    listOf(
        Color(0xFF006C4C),
        Color(0xFF365E9D),
        Color(0xFF7A536F),
        Color(0xFF8C4A3C),
        Color(0xFF5F5E62),
    )

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun ThemeSettingsScreen() {
    val context = LocalContext.current
    val preferences = remember { UserPreferencesManager.getInstance(context) }
    val scope = rememberCoroutineScope()

    val useSystemTheme by preferences.useSystemTheme.collectAsState(initial = true)
    val themeMode by
        preferences.themeMode.collectAsState(initial = UserPreferencesManager.THEME_MODE_LIGHT)
    val useCustomColors by preferences.useCustomColors.collectAsState(initial = false)
    val customPrimaryColor by preferences.customPrimaryColor.collectAsState(initial = null)
    val useBackgroundImage by preferences.useBackgroundImage.collectAsState(initial = false)
    val backgroundImageUri by preferences.backgroundImageUri.collectAsState(initial = null)
    val backgroundOpacity by preferences.backgroundImageOpacity.collectAsState(initial = 0.3f)
    val systemFontName by
        preferences.systemFontName.collectAsState(initial = UserPreferencesManager.SYSTEM_FONT_DEFAULT)
    val fontType by
        preferences.fontType.collectAsState(initial = UserPreferencesManager.FONT_TYPE_SYSTEM)
    val fontScale by preferences.fontScale.collectAsState(initial = 1f)

    val backgroundPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                FileUtils.copyFileToInternalStorage(context, uri, "lean_background")?.let { saved ->
                    preferences.saveThemeSettings(
                        useBackgroundImage = true,
                        backgroundImageUri = saved.toString(),
                        backgroundMediaType = UserPreferencesManager.MEDIA_TYPE_IMAGE,
                    )
                }
            }
        }
    val fontPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                FileUtils.copyFileToInternalStorage(context, uri, "lean_font")?.let { saved ->
                    preferences.saveThemeSettings(
                        useCustomFont = true,
                        fontType = UserPreferencesManager.FONT_TYPE_FILE,
                        customFontPath = saved.toString(),
                    )
                }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ThemeSection(title = stringResource(R.string.theme_title_mode)) {
            SettingSwitch(
                title = stringResource(R.string.theme_follow_system),
                description = stringResource(R.string.theme_follow_system_desc),
                checked = useSystemTheme,
                onCheckedChange = { enabled ->
                    scope.launch { preferences.saveThemeSettings(useSystemTheme = enabled) }
                },
            )
            if (!useSystemTheme) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = themeMode == UserPreferencesManager.THEME_MODE_LIGHT,
                        onClick = {
                            scope.launch {
                                preferences.saveThemeSettings(
                                    themeMode = UserPreferencesManager.THEME_MODE_LIGHT,
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.theme_light)) },
                    )
                    FilterChip(
                        selected = themeMode == UserPreferencesManager.THEME_MODE_DARK,
                        onClick = {
                            scope.launch {
                                preferences.saveThemeSettings(
                                    themeMode = UserPreferencesManager.THEME_MODE_DARK,
                                )
                            }
                        },
                        label = { Text(stringResource(R.string.theme_dark)) },
                    )
                }
            }
        }

        ThemeSection(title = stringResource(R.string.theme_title_color)) {
            SettingSwitch(
                title = stringResource(R.string.theme_use_custom_color),
                description = stringResource(R.string.theme_custom_color_desc),
                checked = useCustomColors,
                onCheckedChange = { enabled ->
                    scope.launch { preferences.saveThemeSettings(useCustomColors = enabled) }
                },
            )
            if (useCustomColors) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    leanThemeColors.forEach { color ->
                        val selected = customPrimaryColor == color.toArgb()
                        Box(
                            modifier =
                                Modifier
                                    .size(if (selected) 44.dp else 40.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .clickable {
                                        scope.launch {
                                            preferences.saveThemeSettings(
                                                customPrimaryColor = color.toArgb(),
                                                customSecondaryColor = color.copy(alpha = 0.72f).toArgb(),
                                                useCustomColors = true,
                                            )
                                        }
                                    },
                        )
                    }
                }
            }
        }

        ThemeSection(title = stringResource(R.string.theme_title_background)) {
            SettingSwitch(
                title = stringResource(R.string.theme_use_custom_bg),
                description = stringResource(R.string.theme_custom_bg_desc),
                checked = useBackgroundImage,
                onCheckedChange = { enabled ->
                    scope.launch { preferences.saveThemeSettings(useBackgroundImage = enabled) }
                },
            )
            backgroundImageUri?.let { imageUri ->
                Image(
                    painter = rememberAsyncImagePainter(Uri.parse(imageUri)),
                    contentDescription = stringResource(R.string.theme_background_preview),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
            }
            Button(onClick = { backgroundPicker.launch("image/*") }) {
                Text(stringResource(R.string.theme_select_image))
            }
            Text(
                text = stringResource(R.string.theme_bg_opacity, (backgroundOpacity * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = backgroundOpacity,
                onValueChange = { value ->
                    scope.launch { preferences.saveThemeSettings(backgroundImageOpacity = value) }
                },
                valueRange = 0.1f..1f,
            )
        }

        ThemeSection(title = stringResource(R.string.theme_font_settings)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val fonts =
                    listOf(
                        UserPreferencesManager.SYSTEM_FONT_DEFAULT to R.string.theme_font_default,
                        UserPreferencesManager.SYSTEM_FONT_SERIF to R.string.theme_font_serif,
                        UserPreferencesManager.SYSTEM_FONT_SANS_SERIF to R.string.theme_font_sans_serif,
                        UserPreferencesManager.SYSTEM_FONT_MONOSPACE to R.string.theme_font_monospace,
                        UserPreferencesManager.SYSTEM_FONT_CURSIVE to R.string.theme_font_cursive,
                    )
                fonts.forEach { (font, label) ->
                    FilterChip(
                        selected = fontType == UserPreferencesManager.FONT_TYPE_SYSTEM && systemFontName == font,
                        onClick = {
                            scope.launch {
                                preferences.saveThemeSettings(
                                    useCustomFont = font != UserPreferencesManager.SYSTEM_FONT_DEFAULT,
                                    fontType = UserPreferencesManager.FONT_TYPE_SYSTEM,
                                    systemFontName = font,
                                )
                            }
                        },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
            OutlinedButton(onClick = { fontPicker.launch("*/*") }) {
                Text(stringResource(R.string.select_font_file))
            }
            Text(
                text = stringResource(R.string.font_size_scale_label, String.format("%.2f", fontScale)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = fontScale,
                onValueChange = { value ->
                    scope.launch { preferences.saveThemeSettings(fontScale = value) }
                },
                valueRange = 0.85f..1.3f,
            )
        }

        OutlinedButton(
            onClick = { scope.launch { preferences.resetThemeSettings() } },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.theme_reset))
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun ThemeSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            content()
        }
    }
}

@Composable
private fun SettingSwitch(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
