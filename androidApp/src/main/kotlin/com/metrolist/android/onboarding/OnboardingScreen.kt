package com.metrolist.android.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.continue_action
import com.metrolist.shared.generated.resources.finish
import com.metrolist.shared.generated.resources.no
import com.metrolist.shared.generated.resources.onboarding_notifications_description
import com.metrolist.shared.generated.resources.onboarding_notifications_title
import com.metrolist.shared.generated.resources.yes
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.onboarding.OnboardingPage
import com.metrolist.shared.ui.onboarding.OnboardingPageContent
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun OnboardingScreen(
    migration: LegacyInstallMigration,
    onComplete: (sendCrashReports: Boolean, destination: OnboardingDestination) -> Unit,
    hasLegacyInstallationOverride: Boolean? = null,
) {
    val pages =
        remember {
            buildList {
                add(OnboardingPage.WELCOME)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(OnboardingPage.NOTIFICATIONS)
                }
            }
        }
    var currentPageName by rememberSaveable { mutableStateOf(OnboardingPage.WELCOME.name) }

    fun finishOnboarding() {
        onComplete(false, OnboardingDestination.HOME)
    }

    fun nextPage() {
        val currentIndex = pages.indexOfFirst { it.name == currentPageName }.coerceAtLeast(0)
        if (currentIndex < pages.lastIndex) {
            currentPageName = pages[currentIndex + 1].name
        } else {
            finishOnboarding()
        }
    }

    val notificationsLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Settings.shared.updateNotifications = granted
            finishOnboarding()
        }

    val safePageIndex = pages.indexOfFirst { it.name == currentPageName }.coerceAtLeast(0)

    @Composable
    fun pageContent(index: Int): OnboardingPageContent {
        val page = pages[index]
        return OnboardingPageContent(
            page = page,
            title =
                when (page) {
                    OnboardingPage.WELCOME -> "Добро пожаловать"
                    else -> stringResource(Res.string.onboarding_notifications_title)
                },
            description =
                when (page) {
                    OnboardingPage.WELCOME -> "Твоя музыка, ИИ-волна и офлайн-кэш готовы к работе."
                    else -> stringResource(Res.string.onboarding_notifications_description)
                },
            icon =
                when (page) {
                    OnboardingPage.WELCOME -> MetrolistIcons.Outlined.MusicNote
                    else -> MetrolistIcons.Outlined.NotificationsNone
                },
            step = index + 1,
            stepCount = pages.size,
            primaryLabel =
                when (page) {
                    OnboardingPage.WELCOME -> {
                        if (pages.size > 1) stringResource(Res.string.continue_action) else stringResource(Res.string.finish)
                    }

                    else -> stringResource(Res.string.yes)
                },
            secondaryLabel =
                when (page) {
                    OnboardingPage.NOTIFICATIONS -> stringResource(Res.string.no)
                    else -> null
                },
            isWorking = false,
            error = null,
            backLabel = stringResource(Res.string.back),
            onPrimary = {
                when (page) {
                    OnboardingPage.WELCOME -> nextPage()
                    OnboardingPage.NOTIFICATIONS -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            Settings.shared.updateNotifications = true
                            finishOnboarding()
                        }
                    }

                    else -> finishOnboarding()
                }
            },
            onSecondary =
                when (page) {
                    OnboardingPage.NOTIFICATIONS -> {
                        {
                            Settings.shared.updateNotifications = false
                            finishOnboarding()
                        }
                    }

                    else -> null
                },
            onBack =
                if (index > 0) {
                    { currentPageName = pages[index - 1].name }
                } else {
                    null
                },
        )
    }

    val content = pageContent(safePageIndex)
    val previousContent = if (safePageIndex > 0) pageContent(safePageIndex - 1) else null

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        OnboardingContent(
            content = content,
            previousContent = previousContent,
            preferences = {},
            accounts = emptyList(),
            onLogin = {},
            onRestore = {},
        )
    }
}
