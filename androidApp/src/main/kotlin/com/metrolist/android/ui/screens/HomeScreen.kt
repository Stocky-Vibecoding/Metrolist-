/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.metrolist.android.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.android.LocalMiniPlayerBottomInset
import com.metrolist.android.LocalStartupPresentation
import com.metrolist.android.custom.CustomCatalogProvider
import com.metrolist.android.custom.CustomMediaCacheManager
import com.metrolist.android.playback.LocalPlayerConnection
import com.metrolist.android.ui.component.tabContentTopPadding
import com.metrolist.android.viewmodels.HomeViewModel
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.no_playable_items
import com.metrolist.shared.generated.resources.player_not_ready
import com.metrolist.shared.generated.resources.recognize_music
import com.metrolist.shared.generated.resources.shuffle
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.components.images.AsyncImage
import com.metrolist.shared.ui.icons.MetrolistIcons
import com.metrolist.shared.ui.navigation.LocalRootContentActive
import com.metrolist.shared.ui.screens.CustomHomeShelvesBridge
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import org.jetbrains.compose.resources.stringResource
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject

private enum class HomeChartTab(
    val label: String,
    val queueTitle: String,
) {
    YANDEX("Яндекс", "Чарт Я.Музыки"),
    VK("VK", "Чарт VK"),
    DEEZER("Deezer", "Чарт Deezer"),
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalMaterial3ExpressiveApi::class,
)
@Composable
fun HomeScreen(
    onNavigateToAlbum: (String) -> Unit = {},
    onNavigateToArtist: (String) -> Unit = {},
    onNavigateToPlaylist: (String, String?) -> Unit = { _, _ -> },
    onNavigateToPodcast: (String) -> Unit = {},
    onNavigateToRecognition: () -> Unit = {},
    onNavigateToBrowse: (String, String?, String) -> Unit = { _, _, _ -> },
    onNavigateToVideoBrowse: (String, String?, String) -> Unit = onNavigateToBrowse,
    onNavigateToHomeSection: (HomeShelf) -> Unit = {},
    onNavigateToLibrary: (LibraryFilterType?) -> Unit = {},
    floatingActionsBottomInset: State<Dp>,
    scrollToTopEvents: Flow<Long> = emptyFlow(),
    viewModel: HomeViewModel = koinViewModel<HomeViewModel>(),
) {
    val playerNotReadyText = stringResource(Res.string.player_not_ready)
    val noPlayableItemsText = stringResource(Res.string.no_playable_items)
    val shuffleText = stringResource(Res.string.shuffle)

    val rootActive = LocalRootContentActive.current
    val context = LocalContext.current
    val startupPresentation = LocalStartupPresentation.current
    SideEffect { startupPresentation?.homeCacheReady = true }

    val playerConnection = LocalPlayerConnection.current
    val currentSong by (playerConnection?.currentSong?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(null) })
    val database: MusicDatabase = koinInject()
    val networkState by PlaybackNetworkStateStore.state.collectAsStateWithLifecycle()
    val isOffline = !networkState.connected

    val cachedSongIds by remember(database) {
        database
            .dao()
            .cachedAudioIds()
            .map { it.toSet() }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = emptySet())

    val snapshot by CustomCatalogProvider.homeSnapshot.collectAsState()
    val isLoadingShelves = CustomHomeShelvesBridge.isLoading

    LaunchedEffect(context) {
        CustomCatalogProvider.initDiskCache(context.applicationContext)
        CustomHomeShelvesBridge.requestLoad(force = false)
    }

    var selectedChart by rememberSaveable { mutableStateOf(HomeChartTab.YANDEX) }
    val yandexSongs = remember(snapshot.yandexChart) { snapshot.yandexChart.map { it.toSongItem() } }
    val vkSongs = remember(snapshot.vkChart) { snapshot.vkChart.map { it.toSongItem() } }
    val deezerSongs = remember(snapshot.deezerChart) { snapshot.deezerChart.map { it.toSongItem() } }
    val radarSongs = remember(snapshot.releaseRadar) { snapshot.releaseRadar.map { it.toSongItem() } }

    // Автоматически переключаемся на первый непустой чарт, если текущий пуст
    LaunchedEffect(yandexSongs.size, vkSongs.size, deezerSongs.size) {
        val currentEmpty =
            when (selectedChart) {
                HomeChartTab.YANDEX -> yandexSongs.isEmpty()
                HomeChartTab.VK -> vkSongs.isEmpty()
                HomeChartTab.DEEZER -> deezerSongs.isEmpty()
            }
        if (currentEmpty) {
            selectedChart =
                when {
                    yandexSongs.isNotEmpty() -> HomeChartTab.YANDEX
                    vkSongs.isNotEmpty() -> HomeChartTab.VK
                    deezerSongs.isNotEmpty() -> HomeChartTab.DEEZER
                    else -> selectedChart
                }
        }
    }

    val activeChartSongs =
        when (selectedChart) {
            HomeChartTab.YANDEX -> yandexSongs
            HomeChartTab.VK -> vkSongs
            HomeChartTab.DEEZER -> deezerSongs
        }

    val offlineCachedSongs =
        remember(isOffline, cachedSongIds.size) {
            if (isOffline) CustomMediaCacheManager.getOfflineCachedSongs(limit = 24) else emptyList()
        }

    val isSongPlayable: (SongItem) -> Boolean = { song ->
        !isOffline || song.isLocal || song.id in cachedSongIds || CustomMediaCacheManager.hasLocalFile(song.id)
    }

    val listState = rememberLazyListState()
    var lastScrollToTopEvent by rememberSaveable { mutableLongStateOf(-1L) }
    LaunchedEffect(scrollToTopEvents) {
        scrollToTopEvents.collect { event ->
            if (event != lastScrollToTopEvent) {
                lastScrollToTopEvent = event
                listState.scrollToItem(0)
            }
        }
    }

    var selectedMenuItem by remember { mutableStateOf<YTItem?>(null) }
    val isTv = LocalIsTv.current
    var showFloatingActions by remember { mutableStateOf(true) }
    val fabNestedScrollConnection =
        remember {
            object : NestedScrollConnection {
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource,
                ): Offset {
                    if (source == NestedScrollSource.UserInput) {
                        when {
                            available.y < -1f -> showFloatingActions = false
                            available.y > 1f -> showFloatingActions = true
                        }
                    }
                    return Offset.Zero
                }
            }
        }

    val miniPlayerInset = LocalMiniPlayerBottomInset.current
    val topPadding = tabContentTopPadding()

    fun shuffleHomeContent() {
        val conn = playerConnection
        if (conn == null) {
            Toast.makeText(context, playerNotReadyText, Toast.LENGTH_SHORT).show()
            return
        }
        val pool =
            (activeChartSongs + radarSongs + offlineCachedSongs)
                .distinctBy { it.id }
                .filter(isSongPlayable)
        if (pool.isEmpty()) {
            Toast.makeText(context, noPlayableItemsText, Toast.LENGTH_SHORT).show()
            return
        }
        conn.shuffleQueue(pool, queueTitle = shuffleText)
    }

    fun playFromList(
        songs: List<SongItem>,
        index: Int,
        title: String,
    ) {
        val conn = playerConnection
        if (conn == null) {
            Toast.makeText(context, playerNotReadyText, Toast.LENGTH_SHORT).show()
            return
        }
        val clicked = songs.getOrNull(index) ?: return
        if (!isSongPlayable(clicked)) {
            Toast.makeText(context, "Нет сети — трек не в кэше", Toast.LENGTH_SHORT).show()
            return
        }
        conn.playQueue(songs = songs, startIndex = index, queueTitle = title)
    }

    val pullToRefreshState = rememberPullToRefreshState()

    Box(
        modifier = Modifier.fillMaxSize().nestedScroll(fabNestedScrollConnection),
    ) {
        PullToRefreshBox(
            isRefreshing = isLoadingShelves,
            onRefresh = { CustomHomeShelvesBridge.requestLoad(force = true) },
            state = pullToRefreshState,
            indicator = {
                RefreshIndicator(
                    state = pullToRefreshState,
                    isRefreshing = isLoadingShelves,
                    modifier =
                        Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = topPadding),
                )
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                contentPadding =
                    PaddingValues(
                        top = topPadding + 4.dp,
                        bottom = miniPlayerInset + 24.dp,
                    ),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                modifier =
                    Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { startupPresentation?.homeLaidOut = true }
                        .focusGroup()
                        .focusRestorer(),
            ) {
                // Офлайн-подборка из локального 4 ГБ кэша (показывается, когда нет сети)
                if (isOffline && offlineCachedSongs.isNotEmpty()) {
                    item(key = "home_offline_cached") {
                        HomeOfflineCacheSection(
                            songs = offlineCachedSongs,
                            currentSongId = currentSong?.id,
                            onPlayAt = { idx -> playFromList(offlineCachedSongs, idx, "Доступно офлайн") },
                            onLongClick = { selectedMenuItem = it },
                        )
                    }
                }

                // 1. Единый блок «Чарты» с чипами Яндекс / VK / Deezer
                item(key = "home_unified_charts") {
                    HomeChartsSection(
                        selectedTab = selectedChart,
                        onSelectTab = { selectedChart = it },
                        songs = activeChartSongs,
                        isLoading = isLoadingShelves && activeChartSongs.isEmpty(),
                        currentSongId = currentSong?.id,
                        isSongPlayable = isSongPlayable,
                        onPlayAll = {
                            if (activeChartSongs.isNotEmpty()) {
                                playFromList(activeChartSongs, 0, selectedChart.queueTitle)
                            }
                        },
                        onSongClick = { idx -> playFromList(activeChartSongs, idx, selectedChart.queueTitle) },
                        onSongLongClick = { selectedMenuItem = it },
                        onRetry = { CustomHomeShelvesBridge.requestLoad(force = true) },
                    )
                }

                // 2. Блок «Радар новинок» (свежие релизы любимых артистов)
                if (radarSongs.isNotEmpty() || isLoadingShelves) {
                    item(key = "home_release_radar") {
                        HomeReleaseRadarSection(
                            songs = radarSongs,
                            isLoading = isLoadingShelves && radarSongs.isEmpty(),
                            currentSongId = currentSong?.id,
                            isSongPlayable = isSongPlayable,
                            onPlayAll = {
                                if (radarSongs.isNotEmpty()) {
                                    playFromList(radarSongs, 0, "Радар новинок")
                                }
                            },
                            onSongClick = { idx -> playFromList(radarSongs, idx, "Радар новинок") },
                            onSongLongClick = { selectedMenuItem = it },
                        )
                    }
                }
            }
        }

        if (!isTv) {
            AnimatedVisibility(
                visible = showFloatingActions,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = floatingActionsBottomInset.value),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SmallFloatingActionButton(
                        onClick = onNavigateToRecognition,
                        shape = RoundedCornerShape(8.dp),
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Outlined.AudioCapture,
                            contentDescription = stringResource(Res.string.recognize_music),
                        )
                    }
                    FloatingActionButton(
                        onClick = ::shuffleHomeContent,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Rounded.Shuffle,
                            contentDescription = stringResource(Res.string.shuffle),
                        )
                    }
                }
            }
        }
    }

    HomeItemMenu(
        selectedItem = selectedMenuItem,
        onDismiss = { selectedMenuItem = null },
        onNavigateToAlbum = onNavigateToAlbum,
        onNavigateToArtist = onNavigateToArtist,
        onNavigateToPodcast = onNavigateToPodcast,
        onSongMetadataUpdated = viewModel.shared::updateSongMetadata,
        active = rootActive,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeChartsSection(
    selectedTab: HomeChartTab,
    onSelectTab: (HomeChartTab) -> Unit,
    songs: List<SongItem>,
    isLoading: Boolean,
    currentSongId: String?,
    isSongPlayable: (SongItem) -> Boolean,
    onPlayAll: () -> Unit,
    onSongClick: (Int) -> Unit,
    onSongLongClick: (SongItem) -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Чарты",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (songs.isNotEmpty()) {
                    FilledTonalIconButton(
                        onClick = onPlayAll,
                        modifier = Modifier.size(40.dp),
                        colors =
                            IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ),
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(
                            imageVector = MetrolistIcons.Filled.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HomeChartTab.entries.forEach { tab ->
                    FilterChip(
                        selected = selectedTab == tab,
                        onClick = { onSelectTab(tab) },
                        label = { Text(tab.label) },
                    )
                }
            }

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                    }
                }

                songs.isEmpty() -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text =
                                if (selectedTab == HomeChartTab.VK) {
                                    "Войди в ВК в настройках волны, чтобы видеть чарт VK"
                                } else {
                                    "Чарт пока не загружен"
                                },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = onRetry) {
                            Text("Обновить")
                        }
                    }
                }

                else -> {
                    val columns = remember(songs) { songs.chunked(4) }
                    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                        val columnWidth =
                            if (maxWidth >= 600.dp) {
                                320.dp
                            } else {
                                (maxWidth - 44.dp).coerceIn(256.dp, 320.dp)
                            }
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            itemsIndexed(columns) { colIndex, columnSongs ->
                                Column(
                                    modifier = Modifier.width(columnWidth),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    columnSongs.forEachIndexed { rowIndex, song ->
                                        val rank = colIndex * 4 + rowIndex
                                        val isCurrent = song.id == currentSongId
                                        val playable = isSongPlayable(song)
                                        val artistLine = song.artists.joinToString(", ") { it.name }.trim()

                                        Row(
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(14.dp))
                                                    .background(
                                                        if (isCurrent) {
                                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f)
                                                        } else {
                                                            MaterialTheme.colorScheme.surfaceContainerLow
                                                        },
                                                    ).alpha(if (playable) 1f else 0.45f)
                                                    .combinedClickable(
                                                        onClick = { onSongClick(rank) },
                                                        onLongClick = { onSongLongClick(song) },
                                                    ).padding(horizontal = 6.dp, vertical = 5.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        ) {
                                            Text(
                                                text = "${rank + 1}",
                                                style = MaterialTheme.typography.labelLarge,
                                                fontWeight = FontWeight.Bold,
                                                color =
                                                    if (isCurrent) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurfaceVariant
                                                    },
                                                modifier = Modifier.width(22.dp),
                                            )
                                            Box(
                                                modifier =
                                                    Modifier
                                                        .size(46.dp)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                                contentAlignment = Alignment.Center,
                                            ) {
                                                if (!song.thumbnail.isNullOrBlank()) {
                                                    AsyncImage(
                                                        url = song.thumbnail,
                                                        contentDescription = null,
                                                        modifier = Modifier.fillMaxSize(),
                                                        contentScale = ContentScale.Crop,
                                                    )
                                                } else {
                                                    Icon(
                                                        imageVector = MetrolistIcons.Rounded.MusicNote,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(22.dp),
                                                    )
                                                }
                                            }
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.Center,
                                            ) {
                                                Text(
                                                    text = song.title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color =
                                                        if (isCurrent) {
                                                            MaterialTheme.colorScheme.primary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurface
                                                        },
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                )
                                                if (artistLine.isNotBlank()) {
                                                    Text(
                                                        text = artistLine,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeReleaseRadarSection(
    songs: List<SongItem>,
    isLoading: Boolean,
    currentSongId: String?,
    isSongPlayable: (SongItem) -> Boolean,
    onPlayAll: () -> Unit,
    onSongClick: (Int) -> Unit,
    onSongLongClick: (SongItem) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Радар новинок",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Свежие релизы твоих артистов",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (songs.isNotEmpty()) {
                FilledTonalIconButton(
                    onClick = onPlayAll,
                    modifier = Modifier.size(40.dp),
                    colors =
                        IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.5.dp, modifier = Modifier.size(26.dp))
            }
        } else {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(songs, key = { _, item -> "radar_${item.id}" }) { index, song ->
                    val isCurrent = song.id == currentSongId
                    val playable = isSongPlayable(song)
                    val artistLine = song.artists.joinToString(", ") { it.name }.trim()

                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color =
                            if (isCurrent) {
                                MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerLow
                            },
                        modifier =
                            Modifier
                                .width(148.dp)
                                .alpha(if (playable) 1f else 0.45f)
                                .clip(RoundedCornerShape(18.dp))
                                .combinedClickable(
                                    onClick = { onSongClick(index) },
                                    onLongClick = { onSongLongClick(song) },
                                ),
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(132.dp)
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (!song.thumbnail.isNullOrBlank()) {
                                    AsyncImage(
                                        url = song.thumbnail,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                } else {
                                    Icon(
                                        imageVector = MetrolistIcons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(32.dp),
                                    )
                                }
                            }
                            Column(
                                modifier = Modifier.padding(horizontal = 2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color =
                                        if (isCurrent) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (artistLine.isNotBlank()) {
                                    Text(
                                        text = artistLine,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeOfflineCacheSection(
    songs: List<SongItem>,
    currentSongId: String?,
    onPlayAt: (Int) -> Unit,
    onLongClick: (SongItem) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "Доступно офлайн",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "Из кэша 4 ГБ и лайков (${songs.size})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(
                    onClick = { onPlayAt(0) },
                    modifier = Modifier.size(40.dp),
                    shapes = IconButtonDefaults.shapes(),
                ) {
                    Icon(
                        imageVector = MetrolistIcons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(songs.take(16), key = { _, item -> "offline_${item.id}" }) { index, song ->
                    val isCurrent = song.id == currentSongId
                    val artistLine = song.artists.joinToString(", ") { it.name }.trim()
                    Column(
                        modifier =
                            Modifier
                                .width(116.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .combinedClickable(
                                    onClick = { onPlayAt(index) },
                                    onLongClick = { onLongClick(song) },
                                ).padding(4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(108.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (!song.thumbnail.isNullOrBlank()) {
                                AsyncImage(
                                    url = song.thumbnail,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                )
                            } else {
                                Icon(
                                    imageVector = MetrolistIcons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(28.dp),
                                )
                            }
                        }
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artistLine.isNotBlank()) {
                            Text(
                                text = artistLine,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}
