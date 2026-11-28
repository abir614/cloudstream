package com.lagradost.cloudstream3.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.integerArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.AllLanguagesName
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CommonActivity.activity
import com.lagradost.cloudstream3.CommonActivity.showToast
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.UnsafeSSL
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine
import com.lagradost.cloudstream3.domain.engine.NsfwFilterLevel
import com.lagradost.cloudstream3.insecureApp
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.network.initClient
import com.lagradost.cloudstream3.utils.BatteryOptimizationChecker.isAppRestricted
import com.lagradost.cloudstream3.utils.BatteryOptimizationChecker.showRequestIgnoreBatteryOptDialog
import com.lagradost.cloudstream3.utils.DeviceProfile
import com.lagradost.cloudstream3.utils.ImageLoader
import com.lagradost.cloudstream3.utils.SubtitleHelper.fromTagToLanguageName
import com.lagradost.cloudstream3.utils.SubtitleHelper.getNameNextToFlagEmoji
import com.lagradost.cloudstream4.AppSettings
import com.lagradost.cloudstream4.compose.ActionDialog
import com.lagradost.cloudstream4.compose.PHONE
import com.lagradost.cloudstream4.compose.focusOutline
import com.lagradost.cloudstream4.compose.isLayout
import com.lagradost.cloudstream4.rememberAppSettings
import com.lagradost.cloudstream4.theme.CloudStreamPreviewTheme
import com.lagradost.safefile.SafeFile
import com.mihon.presentation.secondaryItemAlpha
import com.mihon.presentation.settings.Preference
import com.mihon.presentation.settings.SearchableSettings
import com.mihon.presentation.settings.collectAsState
import com.mihon.presentation.settings.widget.BasePreferenceWidget
import com.mihon.presentation.settings.widget.PrefsHorizontalPadding
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentMap

object SettingsGeneralScreen : SearchableSettings {
    @Composable
    override fun getTitleRes(): String = stringResource(R.string.category_general)

    @Composable
    override fun getPreferences(): List<Preference> {
        val settings = rememberAppSettings()

        // TODO Refactor entirely to use a different file path selector ect like QuickNovel
        val selectFileSelector =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                // It lies, it can be null if file manager quits.
                if (uri == null) return@rememberLauncherForActivityResult
                val context = CloudStreamApp.context ?: return@rememberLauncherForActivityResult

                try {
                    val settings = AppSettings(context)
                    // RW perms for the path
                    val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION

                    context.contentResolver.takePersistableUriPermission(uri, flags)

                    val filePath = SafeFile.fromUri(context, uri)?.filePath()
                    println("Selected URI path: $uri - Full path: $filePath")

                    // store the actual URI instead of the path due to permissions.
                    // filePath should only be used for cosmetic purposes.
                    val visual = filePath ?: uri.toString()
                    settings.general.downloadPath.set(uri.toString())
                    settings.general.downloadPathVisual.set(visual)
                } catch (t: Throwable) {
                    logError(t)
                }
            }

        val bananas by settings.general.bananas.collectAsState()
        val downloadPathVisual by settings.general.downloadPathVisual.collectAsState()
        val nsfwGloballyEnabled by settings.general.enableNsfw.collectAsState()
        val nsfwLevel by settings.general.nsfwFilterLevel.collectAsState()
        val thresholdExtreme by settings.general.visualThresholdExtreme.collectAsState()
        val thresholdModerate by settings.general.visualThresholdModerate.collectAsState()
        val thresholdLow by settings.general.visualThresholdLow.collectAsState()

        val activeLevel = NsfwFilterLevel.fromString(nsfwLevel)
        val rawActiveThreshold = when (activeLevel) {
            NsfwFilterLevel.EXTREME -> thresholdExtreme
            NsfwFilterLevel.LOW -> thresholdLow
            NsfwFilterLevel.MODERATE -> thresholdModerate
        }
        val activeThreshold = NsfwFilterEngine.clampThresholdForLevel(rawActiveThreshold, activeLevel)

        var isBatteryShown by remember { mutableStateOf(false) }
        val context = LocalContext.current

        if (isBatteryShown) {
            ActionDialog(
                icon = painterResource(R.drawable.battery_alert_24px),
                title = stringResource(R.string.battery_dialog_title),
                text = stringResource(R.string.battery_dialog_message),
                confirmText = stringResource(R.string.ok),
                dismissText = stringResource(R.string.cancel),
                dismiss = {
                    isBatteryShown = false
                    settings.general.batterOptimization.set(false)
                },
                confirm = {
                    isBatteryShown = false
                    context.showRequestIgnoreBatteryOptDialog()
                }
            )
        }

        val default = AllLanguagesName to stringResource(R.string.all_languages_preference)
        val languages = APIHolder.apis.withLock {
            APIHolder.apis.map { api -> api.lang }.distinct()
        }.sortedBy { fromTagToLanguageName(it) ?: it }

        return persistentListOf(
            Preference.PreferenceGroup(title = stringResource(R.string.extension_language), preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    preference = settings.general.locale,
                    entries = appLanguages.associate { (name, code) -> (code to (name to code).nameNextToFlagEmoji()) },
                    title = stringResource(R.string.app_language),
                    icon = painterResource(R.drawable.language_korean_latin_24px),
                    onValueChanged = { value ->
                        settings.general.locale.set(value)
                        activity?.recreate()
                        return@ListPreference false
                    },
                    subtitleProvider = { v, e -> e[v] ?: getCurrentLocale(LocalContext.current) }
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    title = stringResource(R.string.provider_lang_settings),
                    icon = painterResource(R.drawable.plugin_lang),
                    entries = mapOf(default) + languages.associateWith { lang ->
                        (getNameNextToFlagEmoji(
                            lang
                        ) ?: lang)
                    },
                    preference = settings.provider.extensionLanguages
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    title = stringResource(R.string.preferred_media_settings),
                    icon = painterResource(R.drawable.movie_edit_24px),
                    preference = settings.provider.preferredMedia,
                    entries = TvType.entries.associate {
                        it.ordinal.toString() to stringResource(it.toStringRes())
                    }
                        // Ok this looks strange af, but we do this to avoid double movie
                        .minus(TvType.AnimeMovie.ordinal.toString()).toPersistentMap(), onValueChanged = { diff ->
                        if(diff.contains(TvType.Movie.ordinal.toString())) {
                            settings.provider.preferredMedia.set(diff + TvType.AnimeMovie.ordinal.toString())
                        } else {
                            settings.provider.preferredMedia.set(diff - TvType.AnimeMovie.ordinal.toString())
                        }
                        return@MultiSelectListPreference false
                    }),
            )),

            Preference.PreferenceGroup(
                title = stringResource(R.string.title_downloads),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        icon = painterResource(R.drawable.netflix_download),
                        subtitle = downloadPathVisual,
                        title = stringResource(R.string.download_path_pref),
                        onClick = {
                            // This is not a ListPreference because the old selection system is
                            // broken af. This needs to be refactored to QuickNovels download path
                            // system.
                            selectFileSelector.launch(Uri.EMPTY)
                        },
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        icon = painterResource(R.drawable.arrow_or_edge_24px),
                        preference = settings.general.parallelDownloads,
                        valueRange = 1..10,
                        title = stringResource(R.string.parallel_downloads),
                        subtitle = stringResource(R.string.download_parallel_settings_des),
                    ),
                    Preference.PreferenceItem.SliderPreference(
                        icon = painterResource(R.drawable.arrow_and_edge_24px),
                        preference = settings.general.concurrentConnections,
                        valueRange = 1..10,
                        title = stringResource(R.string.concurrent_connections),
                        subtitle = stringResource(R.string.concurrent_connections_settings_des),
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.battery_dialog_title),
                        icon = painterResource(R.drawable.battery_alert_24px),
                        enabled = isLayout(PHONE),
                        onClick = {
                            if (isAppRestricted(context)) {
                                isBatteryShown = true
                            } else {
                                showToast(R.string.app_unrestricted_toast)
                            }
                        }
                    ),
                )
            ),

            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_performance),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.ListPreference(
                        title = stringResource(R.string.performance_profile_settings),
                        subtitle = stringResource(R.string.performance_profile_settings_des),
                        subtitleProvider = { value, entries ->
                            val selectedLabel = entries[value] ?: value
                            val summary = DeviceProfile.getHardwareSummary(context)
                            if (value == "auto") {
                                "$selectedLabel (Adaptive)\n$summary"
                            } else {
                                "$selectedLabel (Manual Override)\n$summary"
                            }
                        },
                        icon = painterResource(R.drawable.memory_24px),
                        preference = settings.general.performanceProfile,
                        entries = stringArrayResource(R.array.performance_profile_values).zip(
                            stringArrayResource(R.array.performance_profile_entries)
                        ).toMap(),
                        onValueChanged = {
                            (CloudStreamApp.context ?: context).let { ctx ->
                                DeviceProfile.resetCache()
                                ImageLoader.reconfigure(ctx)
                                app.initClient(ctx, ignoreSSL = false)
                                @OptIn(UnsafeSSL::class)
                                insecureApp.initClient(ctx, ignoreSSL = true)
                            }
                            return@ListPreference true
                        },
                    )
                )
            ),

            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_content_safety),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.enable_nsfw_on_providers),
                        subtitle = stringResource(R.string.enable_nsfw_summary),
                        icon = painterResource(R.drawable.ic_outline_remove_red_eye_24),
                        preference = settings.general.enableNsfw,
                        onValueChanged = { enabled ->
                            com.lagradost.cloudstream3.MainAPI.settingsForProvider.enableAdult = enabled
                            com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine.onFilterConfigChanged()
                            true
                        }
                    ),
                    Preference.PreferenceItem.CustomPreference(
                        title = stringResource(R.string.nsfw_filter_level_title),
                        content = {
                            NsfwSensitivityPreferenceCard(
                                activeLevel = activeLevel,
                                activeThreshold = activeThreshold,
                                onSelectLevel = { level ->
                                    settings.general.nsfwFilterLevel.set(level.key)
                                    NsfwFilterEngine.onFilterConfigChanged()
                                },
                                onThresholdChanged = { level, value ->
                                    val clamped = NsfwFilterEngine.clampThresholdForLevel(value, level)
                                    when (level) {
                                        NsfwFilterLevel.EXTREME -> settings.general.visualThresholdExtreme.set(clamped)
                                        NsfwFilterLevel.MODERATE -> settings.general.visualThresholdModerate.set(clamped)
                                        NsfwFilterLevel.LOW -> settings.general.visualThresholdLow.set(clamped)
                                    }
                                    NsfwFilterEngine.onFilterConfigChanged()
                                },
                                onResetDefaults = {
                                    settings.general.visualThresholdExtreme.set(NsfwFilterEngine.DEFAULT_THRESHOLD_EXTREME)
                                    settings.general.visualThresholdModerate.set(NsfwFilterEngine.DEFAULT_THRESHOLD_MODERATE)
                                    settings.general.visualThresholdLow.set(NsfwFilterEngine.DEFAULT_THRESHOLD_LOW)
                                    NsfwFilterEngine.onFilterConfigChanged()
                                }
                            )
                        }
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.censor_nsfw_posters_title),
                        subtitle = stringResource(R.string.censor_nsfw_posters_summary),
                        icon = painterResource(R.drawable.ic_baseline_warning_24),
                        preference = settings.general.censorNsfwPosters,
                        enabled = nsfwGloballyEnabled,
                        onValueChanged = {
                            com.lagradost.cloudstream3.domain.engine.NsfwFilterEngine.onFilterConfigChanged()
                            true
                        }
                    )
                )
            ),

            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_bypass),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.add_site_pref),
                        subtitle = stringResource(R.string.add_site_summary),
                        icon = painterResource(R.drawable.copy_all_24px),
                        onClick = {
                            // TODO refactor into compose
                            if (SettingsGeneral.getCurrent().isEmpty()) {
                                SettingsGeneral.showAdd()
                            } else {
                                SettingsGeneral.showAddOrDelete()
                            }
                        }
                    ),
                    Preference.PreferenceItem.ListPreference(
                        title = stringResource(R.string.dns_pref),
                        subtitle = stringResource(R.string.dns_pref_summary),
                        icon = painterResource(R.drawable.dns_24px),
                        preference = settings.general.dns,
                        entries = integerArrayResource(R.array.dns_pref_values).zip(
                            stringArrayResource(R.array.dns_pref)
                        ).toMap(),
                        onValueChanged = { newValue ->
                            settings.general.dns.set(newValue) // Must set the value before initClient is called
                            (CloudStreamApp.context)?.let { ctx ->
                                app.initClient(ctx, ignoreSSL = false)
                                @OptIn(UnsafeSSL::class)
                                insecureApp.initClient(ctx, ignoreSSL = true)
                                com.lagradost.cloudstream3.utils.ImageLoader.resetImageClient()
                            }
                            return@ListPreference true
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        title = stringResource(R.string.jsdelivr_proxy),
                        subtitle = stringResource(R.string.jsdelivr_proxy_summary),
                        icon = painterResource(R.drawable.wifi_proxy_24px),
                        preference = settings.general.jsdelivrProxy
                    )
                )
            ),

            Preference.PreferenceGroup(
                title = stringResource(R.string.pref_category_links),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.github),
                        subtitle = "https://github.com/abir614/cloudstream",
                        icon = painterResource(R.drawable.ic_github_logo),
                        onClick = {
                            CloudStreamApp.openBrowser("https://github.com/abir614/cloudstream")
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.lightnovel),
                        subtitle = "https://github.com/LagradOst/QuickNovel",
                        icon = painterResource(R.drawable.quick_novel_icon),
                        onClick = {
                            CloudStreamApp.openBrowser("https://github.com/LagradOst/QuickNovel")
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.discord),
                        subtitle = "https://discord.gg/5Hus6fM",
                        icon = painterResource(R.drawable.ic_baseline_discord_24),
                        onClick = {
                            CloudStreamApp.openBrowser("https://discord.gg/5Hus6fM")
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.cs3wiki),
                        subtitle = "https://cloudstream.miraheze.org/",
                        icon = painterResource(R.drawable.description_24px),
                        onClick = {
                            CloudStreamApp.openBrowser("https://cloudstream.miraheze.org/")
                        }
                    ),
                )
            ),
            Preference.PreferenceItem.TextPreference(
                title = stringResource(R.string.benene),
                subtitle = if (bananas == 0) {
                    stringResource(R.string.benene_count_text_none)
                } else {
                    stringResource(R.string.benene_count_text, bananas)
                },
                onClick = {
                    settings.general.bananas.set(bananas + 1)
                },
                icon = painterResource(R.drawable.benene),
            ),
            Preference.PreferenceGroup(
                title = stringResource(R.string.about_cloudstream),
                preferenceItems = persistentListOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.about_nsfw_shield_title),
                        subtitle = stringResource(R.string.about_nsfw_shield_summary),
                        icon = painterResource(R.drawable.ic_outline_remove_red_eye_24),
                        onClick = {
                            activity?.let { act ->
                                androidx.appcompat.app.AlertDialog.Builder(act, R.style.AlertDialogCustom)
                                    .setTitle(R.string.about_nsfw_shield_title)
                                    .setMessage(R.string.about_nsfw_shield_dialog_text)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.about_hardware_adaptive_title),
                        subtitle = stringResource(R.string.about_hardware_adaptive_summary),
                        icon = painterResource(R.drawable.memory_24px),
                        onClick = {
                            activity?.let { act ->
                                androidx.appcompat.app.AlertDialog.Builder(act, R.style.AlertDialogCustom)
                                    .setTitle(R.string.about_hardware_adaptive_title)
                                    .setMessage(R.string.about_hardware_adaptive_summary)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(R.string.about_telemetry_title),
                        subtitle = stringResource(R.string.about_telemetry_summary),
                        icon = painterResource(R.drawable.ic_outline_info_24),
                        onClick = {
                            activity?.let { act ->
                                androidx.appcompat.app.AlertDialog.Builder(act, R.style.AlertDialogCustom)
                                    .setTitle(R.string.about_telemetry_title)
                                    .setMessage(R.string.about_telemetry_summary)
                                    .setPositiveButton(android.R.string.ok, null)
                                    .show()
                            }
                        }
                    )
                )
            ),
            Preference.PreferenceItem.InfoPreference(title = stringResource(R.string.legal_notice_text)),
        )
    }
}


@Composable
private fun NsfwSensitivityPreferenceCard(
    activeLevel: NsfwFilterLevel,
    activeThreshold: Float,
    onSelectLevel: (NsfwFilterLevel) -> Unit,
    onThresholdChanged: (NsfwFilterLevel, Float) -> Unit,
    onResetDefaults: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val formattedValue = remember(activeLevel, activeThreshold) {
        String.format(java.util.Locale.ROOT, "%.2f", activeThreshold)
    }
    var inputText by remember(activeLevel, formattedValue) { mutableStateOf(formattedValue) }

    LaunchedEffect(activeLevel, formattedValue) {
        inputText = formattedValue
    }

    fun commitInput(raw: String) {
        val parsed = raw.trim().replace(',', '.').toFloatOrNull()
        if (parsed != null) {
            val clamped = NsfwFilterEngine.clampThresholdForLevel(parsed, activeLevel)
            inputText = String.format(java.util.Locale.ROOT, "%.2f", clamped)
            onThresholdChanged(activeLevel, clamped)
        } else {
            inputText = formattedValue
        }
    }

    val tierOptions = remember {
        listOf(
            NsfwFilterLevel.EXTREME to R.string.nsfw_threshold_extreme_label,
            NsfwFilterLevel.MODERATE to R.string.nsfw_threshold_moderate_label,
            NsfwFilterLevel.LOW to R.string.nsfw_threshold_low_label,
        )
    }

    val tierDesc = when (activeLevel) {
        NsfwFilterLevel.EXTREME -> stringResource(R.string.nsfw_threshold_extreme_desc)
        NsfwFilterLevel.MODERATE -> stringResource(R.string.nsfw_threshold_moderate_desc)
        NsfwFilterLevel.LOW -> stringResource(R.string.nsfw_threshold_low_desc)
    }
    val rangeHint = remember(activeLevel) {
        String.format(
            java.util.Locale.ROOT,
            "(%.2f - %.2f)",
            activeLevel.minVisualThreshold,
            activeLevel.maxVisualThreshold
        )
    }

    BasePreferenceWidget(
        title = stringResource(R.string.nsfw_filter_level_title),
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_baseline_tune_24),
                contentDescription = null,
            )
        },
        subcomponent = {
            val minVal = activeLevel.minVisualThreshold
            val maxVal = activeLevel.maxVisualThreshold
            val stepCount = (Math.round((maxVal - minVal) * 100f) - 1).coerceAtLeast(0)
            val canDecrement = activeThreshold > minVal + 0.001f
            val canIncrement = activeThreshold < maxVal - 0.001f

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PrefsHorizontalPadding)
            ) {
                Text(
                    text = tierDesc,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .secondaryItemAlpha()
                        .padding(top = 2.dp),
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Single-line mutually exclusive pill switch: Extreme | Moderate | Low
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        tierOptions.forEach { (level, labelRes) ->
                            val isSelected = level == activeLevel
                            Surface(
                                onClick = { onSelectLevel(level) },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(34.dp)
                                    .focusOutline(shape = CircleShape),
                                shape = CircleShape,
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color.Transparent
                                },
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = stringResource(labelRes),
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
                                        ),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Visual Threshold header + compact inline stepper/numeric Pill matching BaseSliderItem
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.nsfw_threshold_input_label).trimEnd(':'),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )

                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .focusOutline(shape = CircleShape)
                                    .clickable(enabled = canDecrement) {
                                        onThresholdChanged(activeLevel, activeThreshold - 0.01f)
                                    }
                                    .alpha(if (canDecrement) 1f else 0.35f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "-",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            BasicTextField(
                                value = inputText,
                                onValueChange = { typed ->
                                    if (typed.length <= 5) {
                                        inputText = typed
                                        if (typed.length >= 4) {
                                            val parsed = typed.trim().replace(',', '.').toFloatOrNull()
                                            if (parsed != null) {
                                                val clamped = NsfwFilterEngine.clampThresholdForLevel(parsed, activeLevel)
                                                val clampedStr = String.format(java.util.Locale.ROOT, "%.2f", clamped)
                                                if (Math.abs(parsed - clamped) > 0.001f) {
                                                    inputText = clampedStr
                                                }
                                                onThresholdChanged(activeLevel, clamped)
                                            }
                                        }
                                    }
                                },
                                singleLine = true,
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    fontFeatureSettings = "tnum",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = TextAlign.Center,
                                ),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        commitInput(inputText)
                                        focusManager.clearFocus()
                                    }
                                ),
                                modifier = Modifier
                                    .width(44.dp)
                                    .onFocusChanged { focusState ->
                                        if (!focusState.isFocused) {
                                            commitInput(inputText)
                                        }
                                    },
                            )

                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .focusOutline(shape = CircleShape)
                                    .clickable(enabled = canIncrement) {
                                        onThresholdChanged(activeLevel, activeThreshold + 0.01f)
                                    }
                                    .alpha(if (canIncrement) 1f else 0.35f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "+",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                Slider(
                    value = activeThreshold.coerceIn(minVal, maxVal),
                    onValueChange = { next ->
                        val clamped = NsfwFilterEngine.clampThresholdForLevel(next, activeLevel)
                        inputText = String.format(java.util.Locale.ROOT, "%.2f", clamped)
                        onThresholdChanged(activeLevel, clamped)
                    },
                    valueRange = minVal..maxVal,
                    steps = stepCount,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusOutline(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = rangeHint,
                        style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                        modifier = Modifier.secondaryItemAlpha(),
                    )
                    Text(
                        text = stringResource(R.string.nsfw_threshold_reset),
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clip(CircleShape)
                            .focusOutline(shape = CircleShape)
                            .clickable(onClick = onResetDefaults)
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    )
}

@PreviewLightDark
@Composable
private fun SettingGeneralPreview() {
    CloudStreamPreviewTheme {
        SettingsGeneralScreen.Content()
    }
}