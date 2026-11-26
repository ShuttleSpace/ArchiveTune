/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.lyrics

import android.content.Context
import android.util.Log
import moe.rukamori.archivetune.modules.ModuleRepository
import moe.rukamori.archivetune.modules.ModuleSongStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics straight from the custom source that produced the track.
 *
 * Only answers for `lt:` media ids: those are the tracks whose source knows the
 * id, and its own lyric (netease translations, kugou word-by-word) beats
 * guessing by title. Everything else fails immediately so the generic providers
 * run as if this one did not exist — matching by title/artist is what lrclib and
 * friends already do better.
 */
@Singleton
class LtModuleLyricsProvider @Inject constructor(
    private val repository: ModuleRepository,
) : LyricsProvider {
    override val name: String = "Custom source"

    override fun isEnabled(context: Context): Boolean = true

    override suspend fun getLyrics(
        id: String,
        title: String,
        artist: String,
        album: String?,
        duration: Int,
    ): Result<String> {
        val parsed = ModuleSongStore.parse(id)
        if (parsed == null) {
            // Most tracks are not ours; log once per play so the debug log page can
            // prove the provider was asked and had nothing to say.
            Log.d(TAG, "skip $id (not a custom-source track)")
            return missing("not a custom-source track")
        }
        val module = runCatching { repository.snapshot().modules }
            .getOrNull()
            ?.firstOrNull { it.id == parsed.first && it.enabled }
            ?: run {
                Log.d(TAG, "skip $id (source ${parsed.first} is not installed or disabled)")
                return missing("source ${parsed.first} is not installed")
            }
        val lyrics = runCatching {
            repository.runtime.lyric(
                id = "${module.id}:${module.revision}",
                source = module.source,
                origin = module.origin,
                trackId = parsed.second,
                settings = module.configuration,
                title = title,
                artist = artist,
                album = album,
                durationSeconds = duration,
            )
        }.onFailure { Log.d(TAG, "fail $id: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
        return if (lyrics.isNullOrBlank()) {
            Log.d(TAG, "miss $id (${module.manifest.name} returned no lyric for ${parsed.second})")
            missing("source returned no lyric")
        } else {
            Log.d(TAG, "hit $id via ${module.manifest.name}: ${lyrics.length} chars")
            Result.success(lyrics)
        }
    }

    private companion object {
        const val TAG = "LTLyric"
    }

    private fun missing(reason: String): Result<String> = Result.failure(NoSuchElementException(reason))
}
