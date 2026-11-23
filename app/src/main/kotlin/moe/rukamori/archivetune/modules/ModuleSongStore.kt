/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modules

import androidx.media3.common.MediaItem
import javax.inject.Inject
import javax.inject.Singleton
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.AlbumEntity
import moe.rukamori.archivetune.db.entities.ArtistEntity
import moe.rukamori.archivetune.db.entities.PlaylistSongMap
import moe.rukamori.archivetune.db.entities.Song
import moe.rukamori.archivetune.db.entities.SongEntity
import moe.rukamori.archivetune.models.MediaMetadata

/**
 * Bridges custom-source tracks into the Room playlist tables. `playlist_song_map` has a
 * foreign key onto `song`, so a synthetic SongEntity with an `lt:` id stands in for the
 * YouTube song. YouTube playback paths ignore these rows; they resolve through [stream]
 * instead.
 */
@Singleton
class ModuleSongStore
    @Inject
    constructor(
        private val database: MusicDatabase,
        private val repository: ModuleRepository,
    ) {
        suspend fun addToPlaylist(
            playlistId: String,
            module: InstalledModule,
            track: ModuleTrack,
        ): Boolean {
            val id = songId(module.id, track.id)
            if (database.moduleSongDao.songInPlaylist(playlistId, id) > 0) return false
            database.moduleSongDao.upsert(songOf(module, track).song)
            database.moduleSongDao.insert(
                PlaylistSongMap(
                    playlistId = playlistId,
                    songId = id,
                    position = database.moduleSongDao.nextPosition(playlistId),
                ),
            )
            return true
        }

        suspend fun stream(songId: String): ModuleStream? {
            val (moduleId, trackId) = parse(songId) ?: return null
            val modules = repository.snapshot().modules
            val module = modules.firstOrNull { it.id == moduleId && it.enabled } ?: return null
            val primary =
                runCatching {
                    repository.runtime.stream(
                        "${module.id}:${module.revision}",
                        module.source,
                        module.origin,
                        trackId,
                        module.configuration,
                    )
                }.getOrNull()
            if (primary != null) return primary
            return supplementStream(modules, module, trackId)
        }

        /**
         * A source can be fine at searching and still have no stream for one track (copyright, VIP).
         * `lt-fleet-lx` aggregates the LX endpoint pools, which only ever resolve a url for a
         * known track, so it is asked as a fallback with the `<源>:<id>` id they expect.
         */
        private suspend fun supplementStream(
            modules: List<InstalledModule>,
            owner: InstalledModule,
            trackId: String,
        ): ModuleStream? {
            val lxSource = LX_SOURCES[owner.id] ?: owner.manifest.id.takeIf { LX_SOURCES.containsValue(it) }
            if (lxSource == null) return null
            val supplement = modules.firstOrNull { it.enabled && STREAM_ONLY_LABEL in it.manifest.labels } ?: return null
            return runCatching {
                repository.runtime.stream(
                    "${supplement.id}:${supplement.revision}",
                    supplement.source,
                    supplement.origin,
                    "$lxSource:$trackId",
                    supplement.configuration,
                )
            }.getOrNull()
        }

        /**
         * Resolves a queued `lt:` item. The queue holds a URI-less item (see [lazyItem]) and this
         * runs when ExoPlayer actually prepares it, so a 200-track playlist costs one resolve per
         * track instead of twenty up front — and long-lived stream URLs cannot rot in the queue.
         */
        /**
     * Like, playlist-add and history all read the `song` table, but a track can reach the player
     * without ever being stored (voice search, the browse tree, a resumed queue). Writing the row
     * from the MediaItem tag keeps those actions working for tracks the user has only played.
     */
    suspend fun ensureRow(item: androidx.media3.common.MediaItem) {
        val id = item.mediaId
        if (!isModuleSong(id)) return
        if (database.moduleSongDao.songById(id) != null) return
        val tag = item.localConfiguration?.tag as? moe.rukamori.archivetune.models.MediaMetadata
        database.moduleSongDao.upsert(
            SongEntity(
                id = id,
                title = tag?.title ?: id,
                duration = tag?.duration ?: -1,
                thumbnailUrl = tag?.thumbnailUrl,
                albumName = tag?.album?.title,
                albumArtist = tag?.artists?.firstOrNull()?.name,
            ),
        )
    }

    suspend fun selection(mediaId: String): ModuleAudioSelection? {
            val (moduleId, trackId) = parse(mediaId) ?: return null
            val module =
                repository
                    .snapshot()
                    .modules
                    .firstOrNull { it.id == moduleId && it.enabled } ?: return null
            val stream =
                runCatching {
                    repository.runtime.stream(
                        "${module.id}:${module.revision}",
                        module.source,
                        module.origin,
                        trackId,
                        module.configuration,
                    )
                }.getOrNull() ?: return null
            return ModuleAudioSelection(
                originalId = mediaId,
                module = module,
                trackId = trackId,
                stream = stream,
                cacheKey = mediaId,
            )
        }

        companion object {
            private const val PREFIX = "lt:"

            /** URI scheme that routes a queue item back into [selection]. */
            const val SCHEME = "lt"

        /** Label that marks a module as stream-only; the search UI hides these. */
        const val STREAM_ONLY_LABEL = "stream-only"

        /** Our source ids to the LX source codes their endpoints understand. */
        private val LX_SOURCES =
            mapOf(
                "kuwo" to "kw",
                "netease" to "wy",
                "kugou" to "kg",
                "qq" to "tx",
            )

            fun songId(
                moduleId: String,
                trackId: String,
            ) = "$PREFIX$moduleId:$trackId"

            fun isModuleSong(id: String) = id.startsWith(PREFIX)

            fun parse(id: String): Pair<String, String>? {
                if (!isModuleSong(id)) return null
                val rest = id.removePrefix(PREFIX)
                val separator = rest.indexOf(':')
                if (separator <= 0 || separator == rest.lastIndex) return null
                return rest.substring(0, separator) to rest.substring(separator + 1)
            }

            /** Pure mapper used by the playlist replacement dialog to pick a source track. */
            fun songOf(
                module: InstalledModule,
                track: ModuleTrack,
            ): Song {
                val albumName = track.album
                return Song(
                    song =
                        SongEntity(
                            id = songId(module.id, track.id),
                            title = track.title,
                            duration = track.durationSeconds ?: -1,
                            thumbnailUrl = track.thumbnailUrl,
                            albumName = albumName,
                            albumArtist = module.manifest.name,
                            explicit = track.explicit ?: false,
                        ),
                    artists = track.artists.map { ArtistEntity(id = it, name = it) },
                    album =
                        albumName?.let {
                            AlbumEntity(
                                id = "lt:$it",
                                title = it,
                                songCount = 1,
                                duration = track.durationSeconds ?: 0,
                            )
                        },
                )
            }

            fun mediaItem(
                song: SongEntity,
                stream: ModuleStream,
            ): MediaItem = builder(song).setUri(stream.url).setMimeType(stream.mimeType).build()

            /**
             * Queue item whose audio is resolved on prepare. Needed because bulk play/shuffle build
             * the queue long before a track starts; resolving eagerly would fire one request per
             * track and hand the player URLs that may have expired by the time it reaches them.
             */
            fun lazyItem(song: SongEntity): MediaItem = builder(song).setUri("$SCHEME:///${android.net.Uri.encode(song.id)}").build()

            private fun builder(song: SongEntity): MediaItem.Builder {
                // The mini player reads localConfiguration.tag, not media3's metadata.
                val tag =
                    MediaMetadata(
                        id = song.id,
                        title = song.title,
                        artists = song.albumArtist?.takeIf { it.isNotBlank() }?.let { listOf(MediaMetadata.Artist(null, it)) }.orEmpty(),
                        // models.MediaMetadata.duration is seconds, same as SongEntity.duration.
                        duration = if (song.duration > 0) song.duration else 0,
                        thumbnailUrl = song.thumbnailUrl,
                        album = song.albumName?.let { MediaMetadata.Album("", it) },
                    )
                return MediaItem
                    .Builder()
                    .setMediaId(song.id)
                    .setMediaMetadata(
                        androidx.media3.common.MediaMetadata.Builder().setTitle(tag.title).setIsPlayable(true).build(),
                    ).setTag(tag)
            }
        }
    }
