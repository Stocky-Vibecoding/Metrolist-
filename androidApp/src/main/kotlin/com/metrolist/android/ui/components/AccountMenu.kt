package com.metrolist.android.ui.components

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.metrolist.android.BuildConfig
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.icons.MetrolistIconsBrand
import com.metrolist.shared.ui.tv.RequestTvFocus
import com.metrolist.shared.ui.tv.tvFocusHighlight

@Composable
fun AccountMenu(
    onClose: () -> Unit,
    onNavigateToLogin: () -> Unit,
    onNavigateToTokenLogin: () -> Unit,
    onNavigateToSwitchChannel: () -> Unit,
    onNavigateToHistory: (() -> Unit)? = null,
    onNavigateToStatistics: () -> Unit,
    onNavigateToIntegrations: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onLogout: () -> Unit,
    onNavigateToLastFm: (() -> Unit)? = null,
) {
    val initialFocusRequester = remember { FocusRequester() }
    RequestTvFocus(initialFocusRequester)

    val lastFmUsername = Settings.shared.lastFmUsername.trim()
    val isLastFmConnected = Settings.shared.lastFmSession.isNotBlank() || lastFmUsername.isNotBlank()
    val openLastFm = {
        onClose()
        if (onNavigateToLastFm != null) {
            onNavigateToLastFm()
        } else {
            onNavigateToIntegrations()
        }
    }

    Dialog(onDismissRequest = onClose) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().widthIn(max = 360.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Карточка профиля Last.fm
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .focusRequester(initialFocusRequester)
                            .tvFocusHighlight(RoundedCornerShape(20.dp))
                            .clickable(onClick = openLastFm),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(48.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = MetrolistIconsBrand.LastFm,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.size(26.dp),
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (lastFmUsername.isNotBlank()) lastFmUsername else "Профиль Last.fm",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text =
                                        if (isLastFmConnected) {
                                            "Last.fm подключён • скробблинг"
                                        } else {
                                            "Войди в Last.fm для синхронизации"
                                        },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        if (!isLastFmConnected) {
                            Button(
                                onClick = openLastFm,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Войти в Last.fm")
                            }
                        }
                    }
                }

                if (onNavigateToHistory != null) {
                    AccountMenuActionRow(
                        icon = MetrolistIcons.Outlined.MusicHistory,
                        title = "История",
                        onClick = {
                            onClose()
                            onNavigateToHistory()
                        },
                    )
                }

                AccountMenuActionRow(
                    icon = MetrolistIcons.Outlined.Settings,
                    title = "Настройки",
                    onClick = {
                        onClose()
                        onNavigateToSettings()
                    },
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Metrolist++ (${BuildConfig.VERSION_NAME})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

@Composable
private fun AccountMenuActionRow(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().tvFocusHighlight(RoundedCornerShape(18.dp)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
