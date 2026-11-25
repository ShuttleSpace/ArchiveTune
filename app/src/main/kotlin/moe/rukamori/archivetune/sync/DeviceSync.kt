package moe.rukamori.archivetune.sync

import android.content.Context
import android.net.Uri
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.PlaylistSortType
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.PlaylistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongArtistMap
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.playback.ExoDownloadService
import java.time.LocalDateTime
import javax.inject.Inject
import javax.inject.Singleton

/** One track on the wire. Custom-source ids (`lt:…`) are what actually resolve on the peer. */
data class SyncTrack(
    val id: String,
    val title: String,
    val artists: String = "",
    val album: String? = null,
    val duration: Int = -1,
    val thumbnailUrl: String? = null,
)

data class SyncPlaylist(
    val name: String,
    val tracks: List<SyncTrack> = emptyList(),
)

data class SyncPayload(
    val favorites: List<SyncTrack> = emptyList(),
    val playlists: List<SyncPlaylist> = emptyList(),
    val downloads: List<SyncTrack> = emptyList(),
) {
    val isEmpty: Boolean
        get() = favorites.isEmpty() && playlists.isEmpty() && downloads.isEmpty()
}

/**
 * Phone ↔ car sync over the local network.
 *
 * Deliberately narrow: it exchanges playlists, favorites and the downloaded-track manifest as
 * ids, and the receiving side re-enqueues the downloads itself. No audio files, no cloud, no
 * silent overwrite — a merge only ever adds.
 */
@Singleton
class DeviceSync
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) {
        suspend fun snapshot(): SyncPayload =
            withContext(Dispatchers.IO) {
                val favorites = database.likedSongsByRowIdAsc().first().map { it.toSyncTrack() }
                val playlists =
                    database
                        .playlists(PlaylistSortType.CREATE_DATE, false)
                        .first()
                        .filter { it.playlist.browseId == null }
                        .map { playlist ->
                            SyncPlaylist(
                                name = playlist.playlist.name,
                                tracks =
                                    database
                                        .playlistSongs(playlist.playlist.id)
                                        .first()
                                        .sortedBy { it.map.position }
                                        .map { it.song.toSyncTrack() },
                            )
                        }
                val downloads = database.downloadedSongsList().map { it.toSyncTrack() }
                SyncPayload(favorites = favorites, playlists = playlists, downloads = downloads)
            }

        /** Adds whatever the peer has and we do not. Returns a one-line summary. */
        suspend fun apply(payload: SyncPayload): String =
            withContext(Dispatchers.IO) {
                var newSongs = 0
                var newFavorites = 0
                var newPlaylists = 0
                var newTracks = 0
                database.withTransaction {
                    for (track in payload.favorites + payload.downloads + payload.playlists.flatMap { it.tracks }) {
                        if (insertSong(track)) newSongs++
                    }
                    for (track in payload.favorites) {
                        val already = database.likedSongDates(listOf(track.id)).isNotEmpty()
                        database.update(
                            track.toSongEntity().copy(liked = true, likedDate = LocalDateTime.now()),
                        )
                        if (!already) newFavorites++
                    }
                    for (playlist in payload.playlists) {
                        val existing =
                            database
                                .playlists(PlaylistSortType.CREATE_DATE, false)
                                .first()
                                .firstOrNull { it.playlist.browseId == null && it.playlist.name == playlist.name }
                        val playlistId =
                            existing?.playlist?.id
                                ?: PlaylistEntity(
                                    name = playlist.name,
                                    browseId = null,
                                    bookmarkedAt = LocalDateTime.now(),
                                    isEditable = true,
                                ).let { entity ->
                                    database.insert(entity)
                                    newPlaylists++
                                    entity.id
                                }
                        val rows = database.playlistSongs(playlistId).first()
                        val present = rows.mapTo(HashSet()) { it.map.songId }
                        var position = rows.maxOfOrNull { it.map.position } ?: -1
                        for (track in playlist.tracks) {
                            if (!present.add(track.id)) continue
                            database.insert(
                                PlaylistSongMap(playlistId = playlistId, songId = track.id, position = ++position),
                            )
                            newTracks++
                        }
                    }
                }
                // The peer's downloads become ours: each source track is resolved lazily by the
                // same downloader the workbench uses, so this is a manifest, not audio.
                for (track in payload.downloads) {
                    runCatching {
                        DownloadService.sendAddDownload(
                            context,
                            ExoDownloadService::class.java,
                            DownloadRequest
                                .Builder(track.id, Uri.parse(track.id))
                                .setCustomCacheKey(track.id)
                                .setData(track.title.toByteArray())
                                .build(),
                            false,
                        )
                    }
                }
                "收藏 +$newFavorites · 歌单 +$newPlaylists · 曲目 +$newTracks · 下载 +${payload.downloads.size}（新曲目 $newSongs）"
            }

        private fun insertSong(track: SyncTrack): Boolean {
            val inserted = database.insert(track.toSongEntity()) >= 0
            if (!inserted) return false
            // Song lists read artists through the relation, so an unlinked song shows a blank line.
            val name = track.artists.substringBefore(',').substringBefore('&').trim()
            if (name.isNotBlank()) {
                val artistId = "lt:artist:$name"
                database.insert(ArtistEntity(id = artistId, name = name, isLocal = true))
                database.insert(SongArtistMap(songId = track.id, artistId = artistId, position = 0))
            }
            return true
        }
    }

private fun Song.toSyncTrack(): SyncTrack {
    val names = artists.joinToString(", ") { it.name }
    return SyncTrack(
        id = song.id,
        title = song.title,
        artists = names.ifBlank { song.albumArtist.orEmpty() },
        album = song.albumName,
        duration = song.duration,
        thumbnailUrl = song.thumbnailUrl,
    )
}

private fun SyncTrack.toSongEntity() =
    SongEntity(
        id = id,
        title = title,
        duration = duration,
        thumbnailUrl = thumbnailUrl,
        albumName = album,
        albumArtist = artists.takeIf { it.isNotBlank() },
    )
