package moe.rukamori.archivetune.recommend

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.ModulePlayEntity
import moe.rukamori.archivetune.modules.InstalledModule
import moe.rukamori.archivetune.modules.ModuleRepository
import moe.rukamori.archivetune.modules.ModuleSongStore
import moe.rukamori.archivetune.modules.ModuleTrack
import moe.rukamori.archivetune.playback.PlayerConnection
import javax.inject.Inject
import javax.inject.Singleton

/** One custom-source track suggested on the home page or under a player. */
data class ModuleRecommendation(
    val module: InstalledModule,
    val track: ModuleTrack,
)

/**
 * Recommendations for custom-source tracks.
 *
 * There is no account and no YouTube backend left, so the only signal we own is the
 * custom-source play history: it names the sources the user actually listens to and the
 * artists they actually play. Both surfaces re-run the normal source search with the
 * artists from that history — no new ranking model, no server.
 */
@Singleton
class ModuleRecommendations
    @Inject
    constructor(
        private val database: MusicDatabase,
        private val modules: ModuleRepository,
    ) {
        /** Seeded from the recent plays so it changes every time the history changes. */
        suspend fun daily(limit: Int = 20): List<ModuleRecommendation> =
            withContext(Dispatchers.IO) {
                val played = database.modulePlayDao.recentOnce(HISTORY_WINDOW)
                if (played.isEmpty()) return@withContext emptyList()
                val installed = modules.snapshot().modules.associateBy { it.id }
                val seeds = LinkedHashMap<String, Pair<InstalledModule, String>>()
                for (play in played) {
                    val module = installed[play.moduleId]?.takeIf { it.enabled } ?: continue
                    val artist = play.artists.substringBefore(',').substringBefore('&').trim()
                    if (artist.isBlank()) continue
                    seeds.putIfAbsent("${module.id}:$artist", module to artist)
                    if (seeds.size >= SEED_LIMIT) break
                }
                if (seeds.isEmpty()) return@withContext emptyList()
                val seen = played.mapTo(HashSet()) { it.mediaId }
                val result = LinkedHashMap<String, ModuleRecommendation>()
                for ((module, artist) in seeds.values) {
                    for (track in search(module, artist)) {
                        val mediaId = ModuleSongStore.songId(module.id, track.id)
                        if (!seen.add(mediaId)) continue
                        result.putIfAbsent(mediaId, ModuleRecommendation(module, track))
                        if (result.size >= limit) break
                    }
                    if (result.size >= limit) break
                }
                result.values.toList()
            }

        /** Same source, same artist, current track removed. */
        suspend fun similar(
            mediaId: String,
            query: String,
            limit: Int = 12,
        ): List<ModuleRecommendation> =
            withContext(Dispatchers.IO) {
                val parsed = ModuleSongStore.parse(mediaId) ?: return@withContext emptyList()
                if (query.isBlank()) return@withContext emptyList()
                val module =
                    modules.snapshot().modules.firstOrNull { it.id == parsed.first && it.enabled }
                        ?: return@withContext emptyList()
                search(module, query)
                    .filterNot { it.id == parsed.second }
                    .distinctBy { it.id }
                    .take(limit)
                    .map { ModuleRecommendation(module, it) }
            }

        private suspend fun search(
            module: InstalledModule,
            query: String,
        ): List<ModuleTrack> =
            runCatching {
                modules.runtime.search(
                    "${module.id}:${module.revision}",
                    module.source,
                    module.origin,
                    query,
                    module.configuration,
                    pageSize = PAGE_SIZE,
                    page = 1,
                )
            }.getOrNull().orEmpty()

        private companion object {
            const val HISTORY_WINDOW = 60
            const val SEED_LIMIT = 4
            const val PAGE_SIZE = 10
        }
    }

/** Resolves a suggested track and hands it to the shared player. */
@Singleton
class ModuleTrackPlayer
    @Inject
    constructor(
        private val modules: ModuleRepository,
        private val database: MusicDatabase,
    ) {
        suspend fun play(
            connection: PlayerConnection,
            module: InstalledModule,
            track: ModuleTrack,
        ) {
            val stream =
                modules.runtime.stream(
                    "${module.id}:${module.revision}",
                    module.source,
                    module.origin,
                    track.id,
                    module.configuration,
                    track,
                )
            // Canonical identity even though the uri is resolved: history, lyrics and the
            // playlist code all key custom-source tracks by `lt:<moduleId>:<trackId>`.
            val mediaId = ModuleSongStore.songId(module.id, track.id)
            val tag =
                moe.rukamori.archivetune.models.MediaMetadata(
                    id = mediaId,
                    title = track.title,
                    artists = track.artists.map { moe.rukamori.archivetune.models.MediaMetadata.Artist(null, it) },
                    duration = track.durationSeconds ?: 0,
                    thumbnailUrl = track.thumbnailUrl,
                    album = track.album?.let { moe.rukamori.archivetune.models.MediaMetadata.Album("", it) },
                )
            // Feed the history the recommendations are seeded from.
            runCatching {
                database.modulePlayDao.record(
                    ModulePlayEntity(
                        mediaId = mediaId,
                        title = track.title,
                        artists = track.artists.joinToString(", "),
                        album = track.album,
                        durationSeconds = track.durationSeconds,
                        thumbnailUrl = track.thumbnailUrl,
                        moduleId = module.id,
                        playedAt = System.currentTimeMillis(),
                    ),
                )
            }
            connection.playNow(
                MediaItem
                    .Builder()
                    .setMediaId(mediaId)
                    .setUri(stream.url)
                    .setMimeType(stream.mimeType)
                    .setMediaMetadata(
                        MediaMetadata
                            .Builder()
                            .setTitle(tag.title)
                            .setArtist(tag.artists.joinToString { it.name })
                            .setIsPlayable(true)
                            .build(),
                    )
                    .setTag(tag)
                    .build(),
                stream.headers,
            )
        }
    }
