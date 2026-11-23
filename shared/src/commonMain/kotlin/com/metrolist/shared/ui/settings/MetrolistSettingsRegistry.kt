/*
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@file:Suppress("FunctionName", "ktlint:standard:no-wildcard-imports")

package com.metrolist.shared.ui.settings

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.metrolist.shared.generated.resources.*
import com.metrolist.shared.ui.icons.MetrolistIcons
import org.jetbrains.compose.resources.stringResource

@Composable
fun rememberMetrolistSettingsRegistry(
    host: SharedSettingsHost,
    @Suppress("UNUSED_PARAMETER") settingsRevision: Int,
): SettingsUiRegistry {
    val builder = SettingsUiRegistryBuilder()
    builder.registerAppearanceSettings(host)
    builder.registerPlaybackSettings(host)
    builder.registerContentSettings(host)
    if (host.platform != SettingsPlatform.ANDROID) {
        builder.registerLyricsSettings(host)
    }
    builder.registerPrivacySettings(host)

    if (SettingsPageId.CUSTOM_ENGINE in host.availablePages) {
        builder.customPage(
            id = SettingsPageId.CUSTOM_ENGINE,
            title = "Источники и ИИ-движок",
            subtitle = "Яндекс, VK, YouTube, Last.fm, Gemini, кэш 4 ГБ и бэкап",
            icon = MetrolistIcons.Outlined.AutoAwesome,
            tone = SettingsTone.PURPLE,
            searchTerms =
                listOf(
                    "Яндекс",
                    "VK",
                    "ВК",
                    "YouTube",
                    "Deezer",
                    "Last.fm",
                    "Gemini",
                    "Волна",
                    "Кэш",
                    "Бэкап",
                    "Импорт",
                    "Нормализация",
                    "Антицензура",
                    "Маскот",
                ),
        )
    }

    @Composable
    fun customPage(
        id: SettingsPageId,
        title: String,
        subtitle: String?,
        icon: ImageVector,
        tone: SettingsTone,
    ) {
        if (id in host.availablePages) {
            val labels =
                when (id) {
                    SettingsPageId.STORAGE -> {
                        buildList {
                            addAll(
                                listOf(
                                    Res.string.downloads,
                                    Res.string.download_location,
                                    Res.string.clear_all_downloads,
                                    Res.string.song_cache,
                                    Res.string.max_song_cache_size,
                                    Res.string.clear_song_cache,
                                    Res.string.image_cache,
                                    Res.string.max_image_cache_size,
                                    Res.string.clear_image_cache,
                                    Res.string.music_video_quality,
                                    Res.string.clear_music_video_cache,
                                ),
                            )
                            if (host.platform == SettingsPlatform.ANDROID) {
                                addAll(
                                    listOf(
                                        Res.string.song_cache_location,
                                        Res.string.song_cache_directory,
                                        Res.string.animated_artwork_cache,
                                        Res.string.clear_animated_artwork_cache,
                                    ),
                                )
                            }
                        }
                    }

                    SettingsPageId.BACKUP_RESTORE -> {
                        listOf(
                            Res.string.action_backup,
                            Res.string.action_restore,
                            Res.string.playlist_imports_title,
                        )
                    }

                    SettingsPageId.ANDROID_AUTO -> {
                        listOf(
                            Res.string.android_auto_pinned_playlists,
                            Res.string.android_auto_pinned_playlist_mode,
                            Res.string.android_auto_quick_add_playlist,
                        )
                    }

                    SettingsPageId.UPDATES -> {
                        listOf(
                            Res.string.updates_auto_check,
                            Res.string.updates_notifications,
                            Res.string.updates_changelogs,
                            Res.string.updates_check,
                        )
                    }

                    SettingsPageId.EQUALIZER -> {
                        listOf(
                            Res.string.metroeq_presets,
                            Res.string.metroeq_profiles,
                            Res.string.equalizer_create_custom,
                            Res.string.import_profile,
                            Res.string.eq_wizard,
                            Res.string.import_from_file,
                        )
                    }

                    SettingsPageId.DEVTOOLS -> {
                        buildList {
                            if (host.platform != SettingsPlatform.IOS) add(Res.string.crash_logs)
                            addAll(
                                listOf(
                                    Res.string.devtools_playback_client,
                                    Res.string.devtools_playback_client_automatic,
                                    Res.string.devtools_playback_client_sabr_first,
                                    Res.string.device_connect_title,
                                ),
                            )
                            if (host.platform == SettingsPlatform.ANDROID) {
                                addAll(
                                    listOf(
                                        Res.string.devtools_playback_benchmark_title,
                                        Res.string.devtools_playback_direct_clients,
                                        Res.string.devtools_playback_sabr_clients,
                                    ),
                                )
                            }
                        }
                    }

                    SettingsPageId.ABOUT -> {
                        listOf(
                            Res.string.credits_creator_section,
                            Res.string.credits_collaborators_section,
                            Res.string.credits_license_name,
                        )
                    }

                    else -> {
                        emptyList()
                    }
                }
            builder.customPage(id, title, subtitle, icon, tone, labels.map { stringResource(it) })
        }
    }

    customPage(
        SettingsPageId.EQUALIZER,
        stringResource(Res.string.metroeq),
        stringResource(Res.string.settings_equalizer_overview),
        MetrolistIcons.Outlined.BarChart,
        SettingsTone.PURPLE,
    )
    customPage(
        SettingsPageId.ANDROID_AUTO,
        stringResource(Res.string.android_auto_title),
        stringResource(Res.string.settings_android_auto_overview),
        MetrolistIcons.Outlined.Settings,
        SettingsTone.ORANGE,
    )
    customPage(
        SettingsPageId.STORAGE,
        stringResource(Res.string.storage),
        host.storageSummary,
        MetrolistIcons.Outlined.Storage,
        SettingsTone.GOLD,
    )
    customPage(
        SettingsPageId.BACKUP_RESTORE,
        stringResource(Res.string.backup_restore_title),
        stringResource(Res.string.settings_backup_restore_description),
        MetrolistIcons.Outlined.SettingsBackupRestore,
        SettingsTone.GOLD,
    )
    customPage(
        SettingsPageId.UPDATES,
        stringResource(Res.string.settings_updates_title),
        settingsPageSubtitle(stringResource(Res.string.settings_updates_overview), host.updateSummary),
        MetrolistIcons.Outlined.Update,
        SettingsTone.NEUTRAL,
    )
    customPage(
        SettingsPageId.DEVTOOLS,
        stringResource(Res.string.devtools_title),
        stringResource(Res.string.settings_devtools_overview),
        MetrolistIcons.Outlined.Settings,
        SettingsTone.NEUTRAL,
    )
    customPage(
        SettingsPageId.ABOUT,
        stringResource(Res.string.settings_about_title),
        stringResource(Res.string.settings_about_overview),
        MetrolistIcons.Outlined.Info,
        SettingsTone.NEUTRAL,
    )
    customPage(
        SettingsPageId.UPDATE_DETAILS,
        stringResource(Res.string.updates_title),
        stringResource(Res.string.settings_release_overview),
        MetrolistIcons.Outlined.Update,
        SettingsTone.NEUTRAL,
    )
    customPage(
        SettingsPageId.PLAYBACK_BENCHMARK,
        stringResource(Res.string.devtools_playback_benchmark_title),
        stringResource(Res.string.devtools_playback_benchmark_description),
        MetrolistIcons.Outlined.Settings,
        SettingsTone.NEUTRAL,
    )
    customPage(
        SettingsPageId.SUPPORTED_LINKS,
        stringResource(Res.string.settings_open_supported_links_title),
        stringResource(Res.string.settings_supported_links_overview),
        MetrolistIcons.Outlined.AddLink,
        SettingsTone.BLUE,
    )

    if (SettingsPageId.CUSTOM_ENGINE in host.availablePages) {
        builder.rootGroup(
            "Волна и сервисы",
            SettingsPageId.CUSTOM_ENGINE,
        )
    }
    builder.rootGroup(
        stringResource(Res.string.settings_interface),
        SettingsPageId.APPEARANCE,
        SettingsPageId.CONTENT,
    )
    builder.rootGroup(
        stringResource(Res.string.settings_playback),
        *buildList {
            add(SettingsPageId.PLAYER)
            if (host.platform != SettingsPlatform.ANDROID) add(SettingsPageId.LYRICS)
        }.toTypedArray(),
    )
    builder.rootGroup(stringResource(Res.string.settings_privacy_security), SettingsPageId.PRIVACY)
    builder.rootGroup(
        stringResource(Res.string.settings_storage_data),
        *listOf(SettingsPageId.STORAGE, SettingsPageId.BACKUP_RESTORE)
            .filter { it in host.availablePages }
            .toTypedArray(),
    )
    builder.rootGroup(
        stringResource(Res.string.settings_application_about),
        *listOf(
            SettingsPageId.ANDROID_AUTO,
            SettingsPageId.UPDATES,
            SettingsPageId.SUPPORTED_LINKS,
            SettingsPageId.ABOUT,
            SettingsPageId.DEVTOOLS,
        ).filter { it in host.availablePages }
            .toTypedArray(),
    )
    return organizeSettingsRegistry(builder.build())
}
