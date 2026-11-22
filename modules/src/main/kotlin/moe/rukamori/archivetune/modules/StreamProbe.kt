/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.modules

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A resolved stream URL is not proof of audio: restricted tracks answer with an HTML
 * error page, and some sources hand out URLs that 404 later. Pulling the first few
 * kilobytes tells the two apart cheaply.
 */
object StreamProbe {
    private const val BYTES = 16 * 1024

    suspend fun isPlayable(stream: ModuleStream): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val connection = (URL(stream.url).openConnection() as HttpURLConnection)
                connection.connectTimeout = 8_000
                connection.readTimeout = 8_000
                connection.instanceFollowRedirects = true
                stream.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                connection.setRequestProperty("Range", "bytes=0-$BYTES")
                connection.setRequestProperty("User-Agent", "Arch/stream-probe")
                try {
                    val status = connection.responseCode
                    val mimeRaw = connection.contentType.orEmpty()
                    val mime = mimeRaw.lowercase()
                    // 3xx means "ask somewhere else"; HttpURLConnection only follows redirects
                    // inside the same protocol, so treat it as unknown instead of unplayable.
                    if (status in 300..399) {
                        android.util.Log.d("LTProbe", "${stream.url.take(60)} status=$status (unknown)")
                        return@runCatching true
                    }
                    if (status !in 200..299) {
                        android.util.Log.d("LTProbe", "${stream.url.take(60)} status=$status")
                        return@runCatching false
                    }
                    if (mime.startsWith("text/") || mime.contains("json") || mime.contains("xml")) {
                        android.util.Log.d("LTProbe", "${stream.url.take(60)} rejected mime=$mimeRaw")
                        return@runCatching false
                    }
                    // InputStream.readNBytes needs API 33 and this app minSdk is 26, so read by hand.
                    val head =
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(1024)
                            var read = 0
                            while (read < buffer.size) {
                                val count = input.read(buffer, read, buffer.size - read)
                                if (count <= 0) break
                                read += count
                            }
                            buffer.copyOf(read)
                        }
                    val ok = audioHead(head, mime)
                    android.util.Log.d(
                        "LTProbe",
                        "${stream.url.take(60)} status=$status mime=$mimeRaw head=" +
                            head.take(4).joinToString("") { "%02x".format(it) } + " ok=$ok",
                    )
                    ok
                } finally {
                    connection.disconnect()
                }
            }.onFailure { android.util.Log.d("LTProbe", "${stream.url.take(60)} failed: ${it.javaClass.simpleName}: ${it.message}") }
                .getOrDefault(false)
        }

    /** Container sniff: ID3/MPEG sync, ADTS, MP4, Ogg, FLAC, RIFF/WAVE, WebM. */
    internal fun audioHead(head: ByteArray, mime: String): Boolean {
        if (head.isEmpty()) return false
        val b = { index: Int -> head[index].toInt() and 0xFF }
        if (head.size >= 3 && b(0) == 0x49 && b(1) == 0x44 && b(2) == 0x33) return true // ID3
        if (head.size >= 2 && b(0) == 0xFF && b(1) and 0xE0 == 0xE0) return true // MPEG/AAC sync
        if (head.size >= 8 && b(4) == 0x66 && b(5) == 0x74 && b(6) == 0x79 && b(7) == 0x70) return true // ftyp
        if (head.size >= 4 && b(0) == 0x4F && b(1) == 0x67 && b(2) == 0x67 && b(3) == 0x53) return true // OggS
        if (head.size >= 4 && b(0) == 0x66 && b(1) == 0x4C && b(2) == 0x61 && b(3) == 0x43) return true // fLaC
        if (head.size >= 12 && b(0) == 0x52 && b(1) == 0x49 && b(2) == 0x46 && b(3) == 0x46) return true // RIFF
        if (head.size >= 4 && b(0) == 0x1A && b(1) == 0x45 && b(2) == 0xDF && b(3) == 0xA3) return true // EBML
        // Unknown container: trust an audio mime outright, reject a generic one.
        return mime.startsWith("audio/") || mime.contains("mpegurl") || mime.contains("octet-stream")
    }
}
