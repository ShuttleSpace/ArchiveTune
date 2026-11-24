/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.Downloader
import kotlinx.coroutines.runBlocking
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.db.entities.FormatEntity
import moe.rukamori.archivetune.modules.ModuleMediaStorage
import moe.rukamori.archivetune.modules.ModuleSongStore
import java.io.IOException

/**
 * Downloads a custom-source (`lt:`) track.
 *
 * The standard downloader resolves YouTube ids through the module layer, which knows nothing
 * about `lt:` ids, so those requests are handed to this class instead: resolve the stream,
 * then let [ModuleMediaStorage] apply its headers to a plain cache download.
 */
internal class CustomSourceDownloader(
    private val request: DownloadRequest,
    private val store: ModuleSongStore,
    private val storage: ModuleMediaStorage,
    private val database: MusicDatabase,
) : Downloader {
    private val lock = Any()
    private var delegate: Downloader? = null
    private var worker: Thread? = null
    private var cancelled = false

    override fun download(progressListener: Downloader.ProgressListener?) {
        synchronized(lock) {
            if (cancelled) throw InterruptedException()
            worker = Thread.currentThread()
        }
        try {
            // Same shape as ModuleSelectingDownloader: resolve on this thread, then delegate.
            val selection =
                runBlocking { store.selection(request.id) }
                    ?: throw IOException("No custom source could resolve ${request.id}").also {
                        android.util.Log.d("LTDl", "resolve failed ${request.id}")
                    }
            android.util.Log.d("LTDl", "download ${request.id} -> ${selection.stream.url.take(48)}")
            val downloader = storage.downloader(request, selection)
            synchronized(lock) {
                if (cancelled) {
                    downloader.cancel()
                    throw InterruptedException()
                }
                delegate = downloader
            }
            downloader.download(progressListener)
            // `download` returns only after the file is complete; the downloaded-songs list joins
            // song with format, so a row here is what makes an lt: track show up as downloaded.
            runBlocking {
                runCatching {
                    val stream = selection.stream
                    database.upsert(
                        FormatEntity(
                            id = request.id,
                            itag = 0,
                            mimeType = stream.mimeType.ifBlank { "audio/mpeg" },
                            codecs = stream.codecs,
                            bitrate = stream.bitrate,
                            sampleRate = stream.sampleRate,
                            contentLength = stream.contentLength,
                            loudnessDb = null,
                            playbackUrl = null,
                            sourceName = selection.module.manifest.name,
                            sourceId = selection.module.id,
                            streamVariant = stream.variant,
                        ),
                    )
                }
            }
        } finally {
            synchronized(lock) {
                worker = null
                delegate = null
            }
        }
    }

    override fun cancel() {
        synchronized(lock) {
            cancelled = true
            delegate?.cancel()
            worker?.interrupt()
        }
    }

    override fun remove() {
        synchronized(lock) { delegate?.remove() }
    }
}
