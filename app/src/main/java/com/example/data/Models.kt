package com.example.data

data class Artist(
    val id: String,
    val name: String,
    val image: String? = null
)

data class Album(
    val id: String,
    val name: String,
    val artist: String,
    val image: String? = null
)

data class Track(
    val id: String,
    val name: String,
    val artist: String,
    val album: String,
    val durationSec: Int,
    val image: String? = null,
    val streamUrl: String? = null,
    val uri: String? = null,
    val queueItemId: String? = null
) {
    val durationString: String
        get() {
            val minutes = durationSec / 60
            val seconds = durationSec % 60
            return String.format("%02d:%02d", minutes, seconds)
        }
}

data class Playlist(
    val id: String,
    val name: String,
    val image: String? = null,
    val trackCount: Int = 0
)

data class Player(
    val id: String,
    val name: String,
    val state: String = "idle", // "playing", "paused", "idle"
    val volume: Int = 50,
    val currentTrackId: String? = null,
    val isMuted: Boolean = false,
    val provider: String = ""
)

data class QueueItem(
    val id: String,
    val track: Track,
    val position: Int
)

/** Reflects the real, live state of a Music Assistant player queue (for remote-control mode). */
data class QueueState(
    val queueId: String,
    val state: String = "idle", // "playing", "paused", "idle", "buffering"
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "off", // "off", "one", "all"
    val elapsedTimeSec: Int = 0,
    val currentTrack: Track? = null
)
