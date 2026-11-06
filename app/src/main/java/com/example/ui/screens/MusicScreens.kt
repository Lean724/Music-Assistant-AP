package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.*
import com.example.playback.sendspin.SendspinState
import com.example.viewmodel.MusicViewModel

@Composable
fun LibraryScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val tracks by viewModel.tracks.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableStateOf("canciones") }
    val accentColor = MaterialTheme.colorScheme.primary
    val gradientStart = remember(accentColor) { accentColor.copy(alpha = 0.15f) }

    // Filtered lists
    val filteredTracks = remember(tracks, searchQuery) {
        if (searchQuery.isBlank()) {
            tracks
        } else {
            tracks.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredArtists = remember(artists, searchQuery) {
        if (searchQuery.isBlank()) {
            artists
        } else {
            artists.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredAlbums = remember(albums, searchQuery) {
        if (searchQuery.isBlank()) {
            albums
        } else {
            albums.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    val filteredPlaylists = remember(playlists, searchQuery) {
        if (searchQuery.isBlank()) {
            playlists
        } else {
            playlists.filter {
                it.name.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(gradientStart, Color(0xFF121212)),
                    startY = 0f,
                    endY = 600f
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 900.dp)
                .fillMaxSize()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            // App Branding / Greeting
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp, top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.title_your_music),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.5).sp
                    ),
                    color = Color.White
                )
                SpeakerIndicator(viewModel = viewModel)
            }

            // Library Horizontal Categories Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val tabs = listOf(
                    "canciones" to stringResource(R.string.tab_tracks),
                    "artistas" to stringResource(R.string.tab_artists),
                    "albumes" to stringResource(R.string.tab_albums),
                    "playlists" to stringResource(R.string.tab_playlists)
                )
                tabs.forEach { (key, label) ->
                    val isActive = selectedTab == key
                    FilterChip(
                        selected = isActive,
                        onClick = { selectedTab = key },
                        label = { Text(label, color = if (isActive) Color.Black else Color.White) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accentColor,
                            containerColor = Color(0xFF2A2A2A)
                        ),
                        border = null,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.testTag("lib_chip_$key")
                    )
                }
            }

        // List Content with animated transitions
        Box(modifier = Modifier.weight(1.0f)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = accentColor)
                }
            } else {
                when (selectedTab) {
                    "canciones" -> {
                        if (tracks.isEmpty()) {
                            EmptyLibraryState("No hay canciones disponibles")
                        } else if (filteredTracks.isEmpty()) {
                            EmptySearchState("No se encontraron canciones para \"$searchQuery\"")
                        } else {
                            val listState = rememberLazyListState()
                            val coroutineScope = rememberCoroutineScope()
                            val sortedTracks = remember(filteredTracks) {
                                filteredTracks.sortedBy { it.name.lowercase() }
                            }
                            val alphabet = ('A'..'Z').toList()

                            Row(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = listState,
                                    verticalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    items(sortedTracks) { track ->
                                        val isCurrent = currentTrack?.id == track.id
                                        TrackItemRow(
                                            track = track,
                                            isCurrent = isCurrent,
                                            onClick = { viewModel.playTrack(track) },
                                            onAddQueue = { viewModel.addToQueue(track) }
                                        )
                                    }
                                }

                                // Translucent, active-only A-Z Sidebar Capsule
                                val activeLetters = remember(sortedTracks) {
                                    alphabet.filter { letter ->
                                        sortedTracks.any { it.name.trim().startsWith(letter, ignoreCase = true) }
                                    }
                                }

                                if (activeLetters.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxHeight()
                                            .width(28.dp)
                                            .padding(vertical = 12.dp, horizontal = 2.dp)
                                            .clip(RoundedCornerShape(14.dp))
                                            .background(Color.White.copy(alpha = 0.03f))
                                            .border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(14.dp))
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .verticalScroll(rememberScrollState()),
                                            verticalArrangement = Arrangement.spacedBy(4.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally
                                        ) {
                                            activeLetters.forEach { letter ->
                                                Box(
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .clip(CircleShape)
                                                        .clickable {
                                                            val index = sortedTracks.indexOfFirst {
                                                                it.name.trim().startsWith(letter, ignoreCase = true)
                                                            }
                                                            if (index != -1) {
                                                                coroutineScope.launch {
                                                                    listState.animateScrollToItem(index)
                                                                }
                                                            }
                                                        },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = letter.toString(),
                                                        color = accentColor,
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    "artistas" -> {
                        if (artists.isEmpty()) {
                            EmptyLibraryState("No hay artistas disponibles")
                        } else if (filteredArtists.isEmpty()) {
                            EmptySearchState("No se encontraron artistas para \"$searchQuery\"")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(filteredArtists) { artist ->
                                    ArtistItemRow(artist = artist)
                                }
                            }
                        }
                    }
                    "albumes" -> {
                        if (albums.isEmpty()) {
                            EmptyLibraryState("No hay álbumes disponibles")
                        } else if (filteredAlbums.isEmpty()) {
                            EmptySearchState("No se encontraron álbumes para \"$searchQuery\"")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(filteredAlbums) { album ->
                                    AlbumItemRow(album = album)
                                }
                            }
                        }
                    }
                    "playlists" -> {
                        if (playlists.isEmpty()) {
                            EmptyLibraryState("No hay playlists disponibles")
                        } else if (filteredPlaylists.isEmpty()) {
                            EmptySearchState("No se encontraron playlists para \"$searchQuery\"")
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                items(filteredPlaylists) { playlist ->
                                    PlaylistItemRow(playlist = playlist)
                                }
                            }
                        }
                    }
                }
            }
        }

        // If a track is playing, add a spacer to prevent the list from being obscured by the floating MiniPlayer and search bar
        if (currentTrack != null) {
            Spacer(modifier = Modifier.height(110.dp))
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Persistent Search bar at the bottom
        TextField(
            value = searchQuery,
            onValueChange = { viewModel.searchQuery.value = it },
            placeholder = { Text(stringResource(R.string.search_placeholder), color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.Gray) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = Color.Gray)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF202020),
                unfocusedContainerColor = Color(0xFF202020),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = accentColor
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .testTag("library_search_input")
        )
        }
    }
}

@Composable
fun EmptySearchState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Search, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(60.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(message, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SearchScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val query by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .padding(16.dp)
    ) {
        Text(
            text = "Buscar",
            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp, top = 24.dp)
        )

        // Spotify style Search input
        TextField(
            value = query,
            onValueChange = { viewModel.searchQuery.value = it },
            placeholder = { Text("¿Qué deseas escuchar?", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar", tint = Color.Gray) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.searchQuery.value = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = Color.Gray)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                focusedTextColor = Color.Black,
                unfocusedTextColor = Color.Black,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent
            ),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
                .testTag("search_input")
        )

        // Search Content
        if (query.isBlank()) {
            // Recommendation tiles / suggestions
            Column(
                modifier = Modifier
                    .weight(1.0f)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Explorar todo",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 18.sp,
                    modifier = Modifier.padding(vertical = 12.dp)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ExploreTile("Podcasts", Color(0xFFE13300), Modifier.weight(1f))
                    ExploreTile("Nuevos Lanzamientos", Color(0xFF1E3264), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ExploreTile("Conciertos", Color(0xFF8400E7), Modifier.weight(1f))
                    ExploreTile("Pop en Español", Color(0xFFE8115B), Modifier.weight(1f))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    ExploreTile("Para entrenar", Color(0xFFF037A5), Modifier.weight(1f))
                    ExploreTile("Relajación", Color(0xFF509BF5), Modifier.weight(1f))
                }
            }
        } else {
            // Results list
            if (searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1.0f).fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No se encontraron canciones para \"$query\"",
                        color = Color.Gray,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    text = "Canciones",
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyColumn(
                    modifier = Modifier.weight(1.0f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(searchResults) { track ->
                        val isCurrent = currentTrack?.id == track.id
                        TrackItemRow(
                            track = track,
                            isCurrent = isCurrent,
                            onClick = { viewModel.playTrack(track) },
                            onAddQueue = { viewModel.addToQueue(track) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QueueScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val queue by viewModel.queue.collectAsStateWithLifecycle()
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val accentColor = MaterialTheme.colorScheme.primary

    var queueSearchQuery by remember { mutableStateOf("") }

    val filteredQueue = remember(queue, queueSearchQuery) {
        if (queueSearchQuery.isBlank()) {
            queue
        } else {
            queue.filter {
                it.name.contains(queueSearchQuery, ignoreCase = true) ||
                it.artist.contains(queueSearchQuery, ignoreCase = true) ||
                it.album.contains(queueSearchQuery, ignoreCase = true)
            }
        }
    }

    val showCurrentTrack = currentTrack != null && (
        queueSearchQuery.isBlank() ||
        currentTrack!!.name.contains(queueSearchQuery, ignoreCase = true) ||
        currentTrack!!.artist.contains(queueSearchQuery, ignoreCase = true) ||
        currentTrack!!.album.contains(queueSearchQuery, ignoreCase = true)
    )

    val isQueueResultEmpty = (filteredQueue.isEmpty() && (!showCurrentTrack || currentTrack == null))

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 850.dp)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp, top = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.queue_title),
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp
                    ),
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                if (queue.isNotEmpty()) {
                    TextButton(
                        onClick = { viewModel.clearQueue() },
                        modifier = Modifier.testTag("clear_queue_button")
                    ) {
                        Text(stringResource(R.string.clear_queue), color = Color(0xFFFF5555), fontWeight = FontWeight.Bold)
                    }
                }
            }

        // Speaker connection badge
        SpeakerIndicator(
            viewModel = viewModel,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier.weight(1.0f).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = Color.DarkGray,
                        modifier = Modifier.size(80.dp).padding(bottom = 16.dp)
                    )
                    Text(
                        text = stringResource(R.string.empty_queue),
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        lineHeight = 20.sp
                    )
                }
            }
        } else if (isQueueResultEmpty) {
            Box(
                modifier = Modifier.weight(1.0f).fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                EmptySearchState(message = "No se encontraron resultados para \"$queueSearchQuery\" en la cola")
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1.0f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (showCurrentTrack) {
                    item {
                        Text(
                            text = stringResource(R.string.now_playing),
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    currentTrack?.let { nowPlaying ->
                        item {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF242424)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TrackCoverImage(
                                        imageUrl = nowPlaying.image,
                                        accentColor = accentColor,
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.size(52.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1.0f)) {
                                        Text(
                                            text = nowPlaying.name,
                                            color = Color.White,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = nowPlaying.artist,
                                            color = accentColor,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Icon(
                                        Icons.Default.VolumeUp,
                                        contentDescription = "Sonando",
                                        tint = accentColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                val otherSongs = filteredQueue.filter { it.id != currentTrack?.id }
                if (otherSongs.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "${stringResource(R.string.up_next)} (${otherSongs.size})",
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    items(otherSongs) { track ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TrackCoverImage(
                                    imageUrl = track.image,
                                    accentColor = accentColor,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.size(44.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1.0f)) {
                                    Text(
                                        text = track.name,
                                        color = Color.White,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = track.artist,
                                        color = Color.Gray,
                                        fontSize = 11.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { viewModel.removeFromQueue(track) },
                                    modifier = Modifier.testTag("remove_queue_${track.id}")
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Eliminar de la cola",
                                        tint = Color.DarkGray,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Space for floating MiniPlayer and search bar
        if (currentTrack != null) {
            Spacer(modifier = Modifier.height(110.dp))
        } else {
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Persistent Search bar at the bottom of QueueScreen
        TextField(
            value = queueSearchQuery,
            onValueChange = { queueSearchQuery = it },
            placeholder = { Text(stringResource(R.string.search_placeholder), color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar en cola", tint = Color.Gray) },
            trailingIcon = {
                if (queueSearchQuery.isNotEmpty()) {
                    IconButton(onClick = { queueSearchQuery = "" }) {
                        Icon(Icons.Default.Clear, contentDescription = "Limpiar", tint = Color.Gray)
                    }
                }
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color(0xFF202020),
                unfocusedContainerColor = Color(0xFF202020),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                cursorColor = accentColor
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .testTag("queue_search_input")
        )
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val currentHost by viewModel.host.collectAsStateWithLifecycle()
    val currentPort by viewModel.port.collectAsStateWithLifecycle()
    val currentUsername by viewModel.username.collectAsStateWithLifecycle()
    val currentPassword by viewModel.password.collectAsStateWithLifecycle()
    val currentToken by viewModel.token.collectAsStateWithLifecycle()
    val isDemoMode by viewModel.useDemoMode.collectAsStateWithLifecycle()
    val currentAccentColorStr by viewModel.accentColor.collectAsStateWithLifecycle()
    val isLocalPlayerEnabled by viewModel.localPlayerEnabled.collectAsStateWithLifecycle()
    val currentLocalPlayerName by viewModel.localPlayerName.collectAsStateWithLifecycle()
    val currentSendspinPort by viewModel.sendspinPort.collectAsStateWithLifecycle()
    val currentSendspinDelay by viewModel.sendspinDelayMs.collectAsStateWithLifecycle()
    val sendspinState by viewModel.sendspinState.collectAsStateWithLifecycle()
    
    val isTesting by viewModel.isTestingConnection.collectAsStateWithLifecycle()
    val testResult by viewModel.connectionTestResult.collectAsStateWithLifecycle()

    var hostInput by remember(currentHost) { mutableStateOf(currentHost) }
    var portInput by remember(currentPort) { mutableStateOf(currentPort) }
    var usernameInput by remember(currentUsername) { mutableStateOf(currentUsername) }
    var passwordInput by remember(currentPassword) { mutableStateOf(currentPassword) }
    var tokenInput by remember(currentToken) { mutableStateOf(currentToken) }
    var demoInput by remember(isDemoMode) { mutableStateOf(isDemoMode) }
    var accentColorInput by remember(currentAccentColorStr) { mutableStateOf(currentAccentColorStr) }
    var localPlayerNameInput by remember(currentLocalPlayerName) { mutableStateOf(currentLocalPlayerName) }
    var sendspinPortInput by remember(currentSendspinPort) { mutableStateOf(currentSendspinPort) }
    var sendspinDelayInput by remember(currentSendspinDelay) { mutableStateOf(currentSendspinDelay) }

    var showServerSubmenu by remember { mutableStateOf(false) }
    var insecureConnectionConfirmed by remember(hostInput, portInput) { mutableStateOf(false) }
    val isInsecureConnection = !hostInput.trim().startsWith("https://", ignoreCase = true)

    BackHandler(enabled = showServerSubmenu) {
        showServerSubmenu = false
    }

    val accentColor = remember(accentColorInput) {
        try {
            Color(android.graphics.Color.parseColor(accentColorInput))
        } catch (e: Exception) {
            Color(0xFF0EA5E9)
        }
    }

    val rainbowColors = listOf(
        "#0EA5E9" to "Celeste",
        "#EF4444" to "Rojo",
        "#F97316" to "Naranja",
        "#EAB308" to "Amarillo",
        "#22C55E" to "Verde",
        "#3B82F6" to "Azul",
        "#A855F7" to "Violeta"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121212)),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (showServerSubmenu) {
                // SUBMENU LAYOUT
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { showServerSubmenu = false }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back), tint = Color.White)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.server_config),
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Music Assistant Server",
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            fontSize = 16.sp,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // Host Input
                        Text(stringResource(R.string.server_host), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        TextField(
                            value = hostInput,
                            onValueChange = { hostInput = it },
                            placeholder = { Text("192.168.1.100", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2A2A2A),
                                unfocusedContainerColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )

                        // Port Input
                        Text(stringResource(R.string.server_port), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        TextField(
                            value = portInput,
                            onValueChange = { portInput = it },
                            placeholder = { Text("8095", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2A2A2A),
                                unfocusedContainerColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )

                        // Username Input
                        Text(stringResource(R.string.server_username), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        TextField(
                            value = usernameInput,
                            onValueChange = { usernameInput = it },
                            placeholder = { Text(stringResource(R.string.optional), color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2A2A2A),
                                unfocusedContainerColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )

                        // Password Input
                        Text(stringResource(R.string.server_password), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        TextField(
                            value = passwordInput,
                            onValueChange = { passwordInput = it },
                            placeholder = { Text(stringResource(R.string.optional), color = Color.Gray) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2A2A2A),
                                unfocusedContainerColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )

                        // Long-Lived Token Input
                        Text(stringResource(R.string.server_token), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        TextField(
                            value = tokenInput,
                            onValueChange = { tokenInput = it },
                            placeholder = { Text(stringResource(R.string.optional), color = Color.Gray) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2A2A2A),
                                unfocusedContainerColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                        )
                    }
                }

                        if (isInsecureConnection) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF3A2B16)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Conexión sin cifrado (HTTP/WS)", color = Color(0xFFFFD180), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text(
                                        text = "El usuario, la contraseña, los tokens y los datos intercambiados pueden quedar expuestos a otros dispositivos de la red. En redes locales puede ser necesario para servidores sin TLS. Para servidores remotos, utiliza HTTPS/WSS.",
                                        color = Color.White, fontSize = 12.sp, lineHeight = 16.sp,
                                        modifier = Modifier.padding(top = 6.dp)
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                                        Checkbox(checked = insecureConnectionConfirmed, onCheckedChange = { insecureConnectionConfirmed = it })
                                        Text("Entiendo el riesgo y quiero continuar sin cifrado", color = Color.White, fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                // Test & Save buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Test Connection
                    Button(
                        onClick = {
                            viewModel.saveSettings(hostInput, portInput, usernameInput, passwordInput, tokenInput, demoInput, accentColorInput)
                            viewModel.testConnection()
                        },
                        enabled = !isTesting && hostInput.isNotEmpty() && (!isInsecureConnection || insecureConnectionConfirmed),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A)),
                        modifier = Modifier.weight(1f).testTag("test_connection_button")
                    ) {
                        if (isTesting) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                        } else {
                            Text(stringResource(R.string.test_connection), color = Color.White)
                        }
                    }

                    // Save Settings
                    Button(
                        onClick = {
                            viewModel.saveSettings(hostInput, portInput, usernameInput, passwordInput, tokenInput, demoInput, accentColorInput)
                            showServerSubmenu = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                        enabled = hostInput.isNotEmpty() && (!isInsecureConnection || insecureConnectionConfirmed),
                        modifier = Modifier.weight(1f).testTag("submit_button")
                    ) {
                        Text(stringResource(R.string.save), color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }

                // Connection outcome message
                if (testResult.isNotEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = if (testResult.contains("✓")) Color(0xFF143020) else Color(0xFF421C1C)
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (testResult.contains("✓")) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (testResult.contains("✓")) accentColor else Color(0xFFFFAAAA),
                                modifier = Modifier.size(20.dp).padding(end = 6.dp)
                            )
                            Text(
                                text = testResult,
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            } else {
            // MAIN SETTINGS LAYOUT
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White,
                modifier = Modifier.padding(bottom = 24.dp, top = 24.dp)
            )

            // 1. Connection Section Card (Submenu Item)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showServerSubmenu = true }
                    .padding(bottom = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Dns,
                            contentDescription = stringResource(R.string.server_config),
                            tint = accentColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.server_config),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            val statusText = if (isDemoMode) stringResource(R.string.server_status_demo) else if (currentHost.isNotEmpty()) stringResource(R.string.server_status_configured, currentHost) else stringResource(R.string.server_status_unconfigured)
                            Text(
                                text = statusText,
                                color = Color.Gray,
                                fontSize = 12.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "Ir",
                        tint = Color.Gray
                    )
                }
            }

            // 2. Personalization & Mode Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = stringResource(R.string.personalization),
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    // Dynamic Accent Color Picker
                    Text(
                        text = stringResource(R.string.accent_color),
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rainbowColors.forEach { (hex, name) ->
                            val isSelected = accentColorInput.equals(hex, ignoreCase = true)
                            val colorVal = Color(android.graphics.Color.parseColor(hex))
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(colorVal)
                                    .border(
                                        width = if (isSelected) 3.dp else 0.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        accentColorInput = hex
                                        // Save immediately
                                        viewModel.saveSettings(hostInput, portInput, usernameInput, passwordInput, tokenInput, demoInput, hex)
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = name,
                                        tint = if (hex == "#EAB308") Color.Black else Color.White,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 12.dp))

                    // Sandbox/Demo Switch
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.demo_mode_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text(stringResource(R.string.demo_mode_desc), color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                        Switch(
                            checked = demoInput,
                            onCheckedChange = {
                                demoInput = it
                                // Save immediately on switch toggle
                                viewModel.saveSettings(hostInput, portInput, usernameInput, passwordInput, tokenInput, it, accentColorInput)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentColor,
                                checkedTrackColor = accentColor.copy(alpha = 0.3f)
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 3. Local Player (Sendspin) Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(stringResource(R.string.local_player_title), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                stringResource(R.string.local_player_desc),
                                color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp
                            )
                        }
                        Switch(
                            checked = isLocalPlayerEnabled,
                            onCheckedChange = { enabled ->
                                viewModel.setLocalPlayerConfig(enabled, localPlayerNameInput, sendspinPortInput)
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = accentColor,
                                checkedTrackColor = accentColor.copy(alpha = 0.3f)
                            )
                        )
                    }

                    if (isLocalPlayerEnabled) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 12.dp))

                        // Live connection status
                        val (statusText, statusColor) = when (sendspinState) {
                            is SendspinState.Disconnected -> stringResource(R.string.status_disconnected) to Color.Gray
                            is SendspinState.Connecting -> stringResource(R.string.status_connecting) to Color(0xFFEAB308)
                            is SendspinState.Connected -> stringResource(R.string.status_connected_waiting) to accentColor
                            is SendspinState.Playing -> stringResource(R.string.status_playing_synced) to Color(0xFF22C55E)
                            is SendspinState.Buffering -> stringResource(R.string.status_syncing) to Color(0xFFEAB308)
                            is SendspinState.Error -> "Error: ${(sendspinState as SendspinState.Error).message}" to Color(0xFFEF4444)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(statusText, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        }

                        Text(stringResource(R.string.device_name), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        TextField(
                            value = localPlayerNameInput,
                            onValueChange = { localPlayerNameInput = it },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2A2A2A),
                                unfocusedContainerColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )

                        Text(stringResource(R.string.sendspin_port), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                        TextField(
                            value = sendspinPortInput,
                            onValueChange = { sendspinPortInput = it },
                            placeholder = { Text("8927", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color(0xFF2A2A2A),
                                unfocusedContainerColor = Color(0xFF2A2A2A),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedBorderColor = accentColor,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                        )

                        // Speaker Delay Compensation (retardo de altavoz)
                        Text(stringResource(R.string.speaker_delay), color = Color.LightGray, fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
                        Text(
                            stringResource(R.string.speaker_delay_desc),
                            color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp, modifier = Modifier.padding(bottom = 6.dp)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            Slider(
                                value = sendspinDelayInput.toFloat(),
                                onValueChange = {
                                    sendspinDelayInput = it.toInt()
                                    viewModel.setSendspinDelay(sendspinDelayInput)
                                },
                                valueRange = -100f..500f,
                                steps = 11,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = accentColor,
                                    activeTrackColor = accentColor,
                                    inactiveTrackColor = Color(0xFF333333)
                                )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${sendspinDelayInput} ms",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(55.dp),
                                textAlign = TextAlign.End
                            )
                        }

                        // Presets
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(0 to "0 ms", 150 to "150 ms", 300 to "300 ms (BT)").forEach { (ms, label) ->
                                val isSelected = sendspinDelayInput == ms
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (isSelected) accentColor.copy(alpha = 0.2f) else Color(0xFF2A2A2A))
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) accentColor else Color.Transparent,
                                            shape = RoundedCornerShape(6.dp)
                                        )
                                        .clickable {
                                            sendspinDelayInput = ms
                                            viewModel.setSendspinDelay(ms)
                                        }
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        color = if (isSelected) accentColor else Color.LightGray,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }

                        Button(
                            onClick = { viewModel.setLocalPlayerConfig(true, localPlayerNameInput, sendspinPortInput, sendspinDelayInput) },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.apply_reconnect), color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        Text(
                            stringResource(R.string.server_info_helper),
                            color = Color.Gray, fontSize = 11.sp, lineHeight = 14.sp,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 4. About & Attributions Card
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = stringResource(R.string.about_title),
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "MMAC: Minimal Music Assistant Companion",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Text(
                                text = stringResource(R.string.about_version),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.about_disclaimer),
                        color = Color.LightGray,
                        fontSize = 12.sp,
                        lineHeight = 16.sp
                    )

                    HorizontalDivider(color = Color.White.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 12.dp))

                    Text(
                        text = stringResource(R.string.about_attributions),
                        color = accentColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )

                    Text(
                        text = "• Music Assistant: Servidor multimedia y orquestador creado originalmente por Marcel van der Veldt y la comunidad de Music Assistant (music-assistant.io).\n• Protocolo Sendspin: Especificación abierta de streaming sincronizado multi-habitación (sendspin-audio.com / Open Home Foundation).\n• Music Assistant Mobile App: Aplicación oficial de referencia (github.com/music-assistant/mobile-app).",
                        color = Color.Gray,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
        }
    }
}

// Subcomponents

@Composable
fun ExploreTile(title: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(color)
            .clickable { /* No-op in exploration */ }
            .padding(12.dp)
    ) {
        Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            modifier = Modifier.align(Alignment.TopStart)
        )
    }
}

@Composable
fun TrackItemRow(
    track: Track,
    isCurrent: Boolean,
    onClick: () -> Unit,
    onAddQueue: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackCoverImage(
            imageUrl = track.image,
            accentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1.0f)) {
            Text(
                text = track.name,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${track.artist} • ${track.album}",
                color = Color.Gray,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = track.durationString,
            color = Color.Gray,
            fontSize = 12.sp,
            modifier = Modifier.padding(end = 4.dp)
        )
        IconButton(
            onClick = onAddQueue,
            modifier = Modifier.testTag("add_queue_${track.id}")
        ) {
            Icon(Icons.Outlined.QueueMusic, contentDescription = "Añadir a la cola", tint = Color.LightGray)
        }
    }
}

@Composable
fun ArtistItemRow(artist: Artist) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackCoverImage(
            imageUrl = artist.image,
            accentColor = MaterialTheme.colorScheme.primary,
            isCircle = true,
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Text(
            text = artist.name,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp
        )
    }
}

@Composable
fun AlbumItemRow(album: Album) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackCoverImage(
            imageUrl = album.image,
            accentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = album.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                text = album.artist,
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun PlaylistItemRow(playlist: Playlist) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackCoverImage(
            imageUrl = playlist.image,
            accentColor = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier.size(52.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = playlist.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp
            )
            Text(
                text = "${playlist.trackCount} canciones",
                color = Color.Gray,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun EmptyLibraryState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.LibraryMusic, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(60.dp))
            Spacer(modifier = Modifier.height(12.dp))
            Text(message, color = Color.Gray, textAlign = TextAlign.Center)
        }
    }
}

// Bottom Mini Player Component
@Composable
fun MiniPlayer(
    viewModel: MusicViewModel,
    onExpand: () -> Unit
) {
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentPositionSec by viewModel.currentPositionSec.collectAsStateWithLifecycle()
    val durationSec by viewModel.durationSec.collectAsStateWithLifecycle()

    if (currentTrack != null) {
        val track = currentTrack!!
        val progress = if (durationSec > 0) {
            (currentPositionSec.toFloat() / durationSec.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF242424))
                .clickable { onExpand() }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp)
                    .padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackCoverImage(
                    imageUrl = track.image,
                    accentColor = MaterialTheme.colorScheme.primary,
                    shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1.0f)) {
                    Text(
                        text = track.name,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        color = Color.LightGray,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(
                    onClick = { viewModel.skipPrevious() },
                    modifier = Modifier.testTag("mini_prev")
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Anterior", tint = Color.White)
                }
                IconButton(
                    onClick = { viewModel.togglePlayPause() },
                    modifier = Modifier.testTag("play_pause_button")
                ) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Reproducir o Pausar",
                        tint = Color.White
                    )
                }
                IconButton(
                    onClick = { viewModel.skipNext() },
                    modifier = Modifier.testTag("mini_next")
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Siguiente", tint = Color.White)
                }
            }

            val accentColorHex by viewModel.accentColor.collectAsStateWithLifecycle()
            val accentColor = remember(accentColorHex) {
                try {
                    Color(android.graphics.Color.parseColor(accentColorHex))
                } catch (e: Exception) {
                    Color(0xFF388E3C)
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(3.dp)
                    .background(accentColor)
                    .align(Alignment.BottomStart)
            )
        }
    }
}

// Full Immersive Player Overlay/Sheet Dialog
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullScreenPlayerDialog(
    viewModel: MusicViewModel,
    onClose: () -> Unit
) {
    val currentTrack by viewModel.currentTrack.collectAsStateWithLifecycle()
    val isPlaying by viewModel.isPlaying.collectAsStateWithLifecycle()
    val position by viewModel.currentPositionSec.collectAsStateWithLifecycle()
    val duration by viewModel.durationSec.collectAsStateWithLifecycle()
    val shuffleEnabled by viewModel.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatEnabled by viewModel.repeatEnabled.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    
    val availablePlayers by viewModel.availablePlayers.collectAsStateWithLifecycle()
    val activePlayer by viewModel.activePlayer.collectAsStateWithLifecycle()
    val isRefreshingPlayers by viewModel.isRefreshingPlayers.collectAsStateWithLifecycle()

    var showPlayerPicker by remember { mutableStateOf(false) }

    // Decouple the sliders' displayed value from the live (ticking) ViewModel state while the
    // user is actively dragging, otherwise every automatic position/volume update mid-drag makes
    // the thumb "fight" the user's finger and jump around. We only commit (seek/setVolume) once,
    // when the drag finishes.
    var draggingProgress by remember { mutableStateOf<Float?>(null) }
    var draggingVolume by remember { mutableStateOf<Float?>(null) }

    if (currentTrack == null) {
        onClose()
        return
    }

    val track = currentTrack!!

    // Smooth cover art animation when playing
    val rotationState = rememberInfiniteTransition()
    val rotation by rotationState.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(18000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("REPRODUCIENDO DESDE EL SERVIDOR", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                        Text(activePlayer?.name ?: "Altavoz Music Assistant", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Cerrar", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showPlayerPicker = true }) {
                        Icon(Icons.Default.Cast, contentDescription = "Salida de audio", tint = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color(0xFF0F0F0F)
                )
            )
        },
        containerColor = Color(0xFF0F0F0F)
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), Color(0xFF0F0F0F)),
                        startY = 0f,
                        endY = 1200f
                    )
                )
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            val isTabletMode = maxWidth >= 600.dp
            val livePercent = if (duration > 0) position.toFloat() / duration else 0f
            val displayedPercent = draggingProgress ?: livePercent
            val displayedPositionSec = if (draggingProgress != null) (draggingProgress!! * duration).toInt() else position

            if (isTabletMode) {
                // Tablet Landscape / Wide Screen: 2 Columns
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .widthIn(max = 1000.dp),
                    horizontalArrangement = Arrangement.spacedBy(36.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left Column: Album Cover
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(320.dp),
                            shape = RoundedCornerShape(24.dp),
                            tonalElevation = 8.dp,
                            shadowElevation = 14.dp
                        ) {
                            TrackCoverImage(
                                imageUrl = track.image,
                                accentColor = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(24.dp),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // Right Column: Controls, Sliders, Title
                    Column(
                        modifier = Modifier
                            .weight(1.2f)
                            .fillMaxHeight()
                            .padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.SpaceEvenly,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Title and artist
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1.0f)) {
                                Text(
                                    text = track.name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { }) {
                                Icon(Icons.Default.FavoriteBorder, contentDescription = "Guardar", tint = Color.White)
                            }
                        }

                        // Progress Slider
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Slider(
                                value = displayedPercent,
                                onValueChange = { percent -> draggingProgress = percent },
                                onValueChangeFinished = {
                                    val targetSec = ((draggingProgress ?: livePercent) * duration).toInt()
                                    viewModel.seekTo(targetSec)
                                    draggingProgress = null
                                },
                                colors = SliderDefaults.colors(
                                    activeTrackColor = MaterialTheme.colorScheme.primary,
                                    inactiveTrackColor = Color.DarkGray,
                                    thumbColor = Color.White
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = String.format("%02d:%02d", displayedPositionSec / 60, displayedPositionSec % 60),
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = String.format("%02d:%02d", duration / 60, duration % 60),
                                    color = Color.Gray,
                                    fontSize = 12.sp
                                )
                            }
                        }

                        // Music Controls Row
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.toggleShuffle() }) {
                                Icon(
                                    imageVector = Icons.Default.Shuffle,
                                    contentDescription = "Aleatorio",
                                    tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }

                            IconButton(onClick = { viewModel.skipPrevious() }) {
                                Icon(
                                    imageVector = Icons.Default.SkipPrevious,
                                    contentDescription = "Anterior",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(76.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary)
                                    .clickable { viewModel.togglePlayPause() },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = "Reproducir o Pausar",
                                    tint = Color.Black,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            IconButton(onClick = { viewModel.skipNext() }) {
                                Icon(
                                    imageVector = Icons.Default.SkipNext,
                                    contentDescription = "Siguiente",
                                    tint = Color.White,
                                    modifier = Modifier.size(40.dp)
                                )
                            }

                            IconButton(onClick = { viewModel.toggleRepeat() }) {
                                Icon(
                                    imageVector = Icons.Default.Repeat,
                                    contentDescription = "Repetir",
                                    tint = if (repeatEnabled) MaterialTheme.colorScheme.primary else Color.White
                                )
                            }
                        }

                        // Volume slider
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.VolumeMute, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                            Slider(
                                value = draggingVolume ?: volume.toFloat(),
                                onValueChange = { draggingVolume = it },
                                onValueChangeFinished = {
                                    viewModel.setVolume((draggingVolume ?: volume.toFloat()).toInt())
                                    draggingVolume = null
                                },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
                                    activeTrackColor = Color.White,
                                    inactiveTrackColor = Color.DarkGray,
                                    thumbColor = Color.White
                                ),
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            } else {
                // Phone Layout: Vertical Column
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    // Giant Pulsing/Spinning Cover Art
                    Box(
                        modifier = Modifier
                            .weight(1.0f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier.size(280.dp),
                            shape = RoundedCornerShape(20.dp),
                            tonalElevation = 8.dp,
                            shadowElevation = 12.dp
                        ) {
                            TrackCoverImage(
                                imageUrl = track.image,
                                accentColor = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(20.dp),
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // Title and artist
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1.0f)) {
                                Text(
                                    text = track.name,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = track.artist,
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { }) {
                                Icon(Icons.Default.FavoriteBorder, contentDescription = "Guardar", tint = Color.White)
                            }
                        }
                    }

                    // Progress Slider
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = displayedPercent,
                            onValueChange = { percent -> draggingProgress = percent },
                            onValueChangeFinished = {
                                val targetSec = ((draggingProgress ?: livePercent) * duration).toInt()
                                viewModel.seekTo(targetSec)
                                draggingProgress = null
                            },
                            colors = SliderDefaults.colors(
                                activeTrackColor = MaterialTheme.colorScheme.primary,
                                inactiveTrackColor = Color.DarkGray,
                                thumbColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = String.format("%02d:%02d", displayedPositionSec / 60, displayedPositionSec % 60),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                            Text(
                                text = String.format("%02d:%02d", duration / 60, duration % 60),
                                color = Color.Gray,
                                fontSize = 11.sp
                            )
                        }
                    }

                    // Music Controls Row
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceAround,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleShuffle() }) {
                            Icon(
                                imageVector = Icons.Default.Shuffle,
                                contentDescription = "Aleatorio",
                                tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }

                        IconButton(onClick = { viewModel.skipPrevious() }) {
                            Icon(
                                imageVector = Icons.Default.SkipPrevious,
                                contentDescription = "Anterior",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { viewModel.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Reproducir o Pausar",
                                tint = Color.Black,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(onClick = { viewModel.skipNext() }) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Siguiente",
                                tint = Color.White,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        IconButton(onClick = { viewModel.toggleRepeat() }) {
                            Icon(
                                imageVector = Icons.Default.Repeat,
                                contentDescription = "Repetir",
                                tint = if (repeatEnabled) MaterialTheme.colorScheme.primary else Color.White
                            )
                        }
                    }

                    // Volume slider
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.VolumeMute, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                        Slider(
                            value = draggingVolume ?: volume.toFloat(),
                            onValueChange = { draggingVolume = it },
                            onValueChangeFinished = {
                                viewModel.setVolume((draggingVolume ?: volume.toFloat()).toInt())
                                draggingVolume = null
                            },
                            valueRange = 0f..100f,
                            colors = SliderDefaults.colors(
                                activeTrackColor = Color.White,
                                inactiveTrackColor = Color.DarkGray,
                                thumbColor = Color.White
                            ),
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                        )
                        Icon(Icons.Default.VolumeUp, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }

    // Modal Sheet selector for output audio players
    if (showPlayerPicker) {
        LaunchedEffect(Unit) {
            viewModel.refreshPlayers()
        }
        AlertDialog(
            onDismissRequest = { showPlayerPicker = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.select_player), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    IconButton(
                        onClick = { viewModel.refreshPlayers() },
                        enabled = !isRefreshingPlayers,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isRefreshingPlayers) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_list), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            },
            containerColor = Color(0xFF222222),
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (availablePlayers.isEmpty()) {
                        Text(
                            text = if (isRefreshingPlayers) stringResource(R.string.searching_players) else stringResource(R.string.no_players_found),
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        availablePlayers.forEach { player ->
                            val isSelected = player.id == activePlayer?.id
                            val isThisDevice = viewModel.isThisDevice(player)
                            val isSendspin = player.id.contains("sendspin", ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.2f) else Color(0xFF1F1F1F))
                                    .clickable {
                                        viewModel.setActivePlayer(player)
                                        showPlayerPicker = false
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        isThisDevice -> Icons.Default.Smartphone
                                        player.id.contains("tv", true) -> Icons.Default.Tv
                                        else -> Icons.Default.Speaker
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = player.name,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        if (isThisDevice) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.device_this_device),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else if (isSendspin) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.badge_sendspin),
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = when {
                                            isSendspin -> "${stringResource(R.string.desc_sendspin_synced)} • ${if (player.state == "playing") stringResource(R.string.status_playing) else stringResource(R.string.status_ready)}"
                                            isThisDevice -> stringResource(R.string.desc_local_standalone)
                                            player.state == "playing" -> stringResource(R.string.status_playing)
                                            else -> stringResource(R.string.status_inactive)
                                        },
                                        fontSize = 11.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.8f) else Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPlayerPicker = false }) {
                    Text(stringResource(R.string.close), color = MaterialTheme.colorScheme.primary)
                }
            }
        )
    }
}

@Composable
fun SpeakerIndicator(
    viewModel: MusicViewModel,
    modifier: Modifier = Modifier
) {
    val isRemote by viewModel.isRemoteSession.collectAsStateWithLifecycle()
    val activePlayer by viewModel.activePlayer.collectAsStateWithLifecycle()
    val availablePlayers by viewModel.availablePlayers.collectAsStateWithLifecycle()
    val accentColorHex by viewModel.accentColor.collectAsStateWithLifecycle()
    val accentColor = remember(accentColorHex) {
        try {
            Color(android.graphics.Color.parseColor(accentColorHex))
        } catch (e: Exception) {
            Color(0xFF388E3C)
        }
    }

    var showPicker by remember { mutableStateOf(false) }

    val isThisDeviceActive = activePlayer != null && viewModel.isThisDevice(activePlayer!!)
    val (icon, name) = if (isRemote && activePlayer != null) {
        val p = activePlayer!!
        val iconDevice = when {
            isThisDeviceActive -> Icons.Default.Smartphone
            p.name.contains("TV", ignoreCase = true) || p.name.contains("pantalla", ignoreCase = true) -> Icons.Default.Tv
            p.name.contains("speaker", ignoreCase = true) || p.name.contains("parlante", ignoreCase = true) || p.name.contains("altavoz", ignoreCase = true) || p.name.contains("salon", ignoreCase = true) -> Icons.Default.Speaker
            else -> Icons.Default.Cast
        }
        val displayName = if (isThisDeviceActive) "${p.name} (${stringResource(R.string.device_this_device)})" else p.name
        iconDevice to displayName
    } else {
        Icons.Default.Smartphone to "${viewModel.getLocalDevicePlayer().name} (${stringResource(R.string.device_this_device)})"
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(accentColor.copy(alpha = 0.15f))
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .clickable { showPicker = true }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = stringResource(R.string.active_speaker),
            tint = accentColor,
            modifier = Modifier.size(16.dp)
        )
        Text(
            text = name,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }

    val isRefreshingPlayers by viewModel.isRefreshingPlayers.collectAsStateWithLifecycle()

    if (showPicker) {
        LaunchedEffect(Unit) {
            viewModel.refreshPlayers()
        }
        AlertDialog(
            onDismissRequest = { showPicker = false },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.select_player), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    IconButton(
                        onClick = { viewModel.refreshPlayers() },
                        enabled = !isRefreshingPlayers,
                        modifier = Modifier.size(32.dp)
                    ) {
                        if (isRefreshingPlayers) {
                            CircularProgressIndicator(color = accentColor, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.refresh_list), tint = accentColor, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            },
            containerColor = Color(0xFF222222),
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (availablePlayers.isEmpty()) {
                        Text(
                            text = if (isRefreshingPlayers) stringResource(R.string.searching_players) else stringResource(R.string.no_players_found),
                            color = Color.Gray,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 16.dp)
                        )
                    } else {
                        availablePlayers.forEach { player ->
                            val isSelected = player.id == activePlayer?.id
                            val isThisDevice = viewModel.isThisDevice(player)
                            val isSendspin = player.id.contains("sendspin", ignoreCase = true)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) accentColor.copy(alpha = 0.2f) else Color(0xFF1F1F1F))
                                    .clickable {
                                        viewModel.setActivePlayer(player)
                                        showPicker = false
                                    }
                                    .padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = when {
                                        isThisDevice -> Icons.Default.Smartphone
                                        player.id.contains("tv", true) -> Icons.Default.Tv
                                        else -> Icons.Default.Speaker
                                    },
                                    contentDescription = null,
                                    tint = if (isSelected) accentColor else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = player.name,
                                            color = if (isSelected) accentColor else Color.White,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp
                                        )
                                        if (isThisDevice) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(accentColor.copy(alpha = 0.2f))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.device_this_device),
                                                    color = accentColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else if (isSendspin) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(4.dp))
                                                    .background(accentColor.copy(alpha = 0.2f))
                                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.badge_sendspin),
                                                    color = accentColor,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Text(
                                        text = when {
                                            isSendspin -> "${stringResource(R.string.desc_sendspin_synced)} • ${if (player.state == "playing") stringResource(R.string.status_playing) else stringResource(R.string.status_ready)}"
                                            isThisDevice -> stringResource(R.string.desc_local_standalone)
                                            player.state == "playing" -> stringResource(R.string.status_playing)
                                            else -> stringResource(R.string.status_inactive)
                                        },
                                        fontSize = 11.sp,
                                        color = if (isSelected) accentColor.copy(alpha = 0.8f) else Color.Gray
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(R.string.close), color = accentColor)
                }
            }
        )
    }
}

@Composable
fun CDPlaceholder(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                Brush.radialGradient(
                    colors = listOf(Color(0xFF2E2E2E), Color(0xFF151515))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        // Concentric Vinyl/CD grooves
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val center = size / 2.0f
            val maxRadius = size.minDimension / 2.0f
            
            // Draw subtle concentric grooves
            drawCircle(
                color = Color.White.copy(alpha = 0.08f),
                radius = maxRadius * 0.85f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.07f),
                radius = maxRadius * 0.70f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )
            drawCircle(
                color = Color.White.copy(alpha = 0.06f),
                radius = maxRadius * 0.55f,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
            )
        }
        
        // CD Center label in accent color
        Box(
            modifier = Modifier
                .fillMaxSize(0.40f)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.80f)),
            contentAlignment = Alignment.Center
        ) {
            // Inner spindle hole
            Box(
                modifier = Modifier
                    .fillMaxSize(0.28f)
                    .clip(CircleShape)
                    .background(Color(0xFF121212))
            )
        }
    }
}

@Composable
fun TrackCoverImage(
    imageUrl: String?,
    accentColor: Color,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(4.dp),
    isCircle: Boolean = false
) {
    var isError by remember(imageUrl) { mutableStateOf(imageUrl.isNullOrEmpty()) }
    
    if (isError) {
        CDPlaceholder(
            accentColor = accentColor,
            modifier = modifier
                .clip(if (isCircle) CircleShape else shape)
        )
    } else {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Cover",
            contentScale = ContentScale.Crop,
            onState = { state ->
                isError = state is coil.compose.AsyncImagePainter.State.Error
            },
            modifier = modifier
                .clip(if (isCircle) CircleShape else shape)
        )
    }
}
