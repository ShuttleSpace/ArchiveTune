package moe.rukamori.archivetune.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/** Both ends share this port; the pairing code is the only secret. */
const val SYNC_PORT = 8642

object SyncJson {
    fun encode(payload: SyncPayload): String =
        JSONObject()
            .put("version", 1)
            .put("favorites", tracks(payload.favorites))
            .put(
                "playlists",
                JSONArray(
                    payload.playlists.map { playlist ->
                        JSONObject()
                            .put("name", playlist.name)
                            .put("tracks", tracks(playlist.tracks))
                    },
                ),
            ).put("downloads", tracks(payload.downloads))
            .toString()

    fun decode(text: String): SyncPayload {
        val root = JSONObject(text)
        val playlists =
            root.optJSONArray("playlists")?.let { array ->
                (0 until array.length()).map { index ->
                    val item = array.getJSONObject(index)
                    SyncPlaylist(
                        name = item.optString("name"),
                        tracks = readTracks(item.optJSONArray("tracks")),
                    )
                }
            }.orEmpty()
        return SyncPayload(
            favorites = readTracks(root.optJSONArray("favorites")),
            playlists = playlists.filter { it.name.isNotBlank() },
            downloads = readTracks(root.optJSONArray("downloads")),
        )
    }

    private fun tracks(list: List<SyncTrack>) =
        JSONArray(
            list.map { track ->
                JSONObject()
                    .put("id", track.id)
                    .put("title", track.title)
                    .put("artists", track.artists)
                    .put("album", track.album)
                    .put("duration", track.duration)
                    .put("thumbnail", track.thumbnailUrl)
            },
        )

    private fun readTracks(array: JSONArray?): List<SyncTrack> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val id = item.optString("id")
            if (id.isBlank()) return@mapNotNull null
            SyncTrack(
                id = id,
                title = item.optString("title", id),
                artists = item.optString("artists"),
                album = item.optString("album").ifBlank { null },
                duration = item.optInt("duration", -1),
                thumbnailUrl = item.optString("thumbnail").ifBlank { null },
            )
        }
    }
}

/**
 * Minimal HTTP/1.1 server: one `POST /push` route plus `/ping`, on a plain ServerSocket — the app
 * already carries OkHttp but no server-side HTTP library, and pulling one in for two routes would
 * not pay for itself.
 */
class SyncServer(private val onPush: suspend (SyncPayload) -> String) {
    private var socket: ServerSocket? = null
    private var acceptor: Job? = null
    val code: String = "%06d".format(Random.nextInt(1_000_000))

    val isRunning: Boolean
        get() = acceptor?.isActive == true

    fun start(scope: CoroutineScope) {
        if (isRunning) return
        val server =
            ServerSocket(SYNC_PORT).apply {
                reuseAddress = true
            }
        socket = server
        acceptor =
            scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    launch(Dispatchers.IO) { handle(client) }
                }
            }
    }

    fun stop() {
        acceptor?.cancel()
        acceptor = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun handle(client: java.net.Socket) {
        client.use { peer ->
            peer.soTimeout = REQUEST_TIMEOUT_MS
            val input = BufferedInputStream(peer.getInputStream())
            val requestLine = readLine(input) ?: return respond(peer, 400, "bad request")
            val parts = requestLine.split(' ')
            if (parts.size < 2) return respond(peer, 400, "bad request")
            val method = parts[0]
            val path = parts[1]
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val separator = line.indexOf(':')
                if (separator > 0) {
                    headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
                }
            }
            if (method == "GET" && path.startsWith("/ping")) return respond(peer, 200, """{"ok":true}""")
            if (method != "POST" || !path.startsWith("/push")) return respond(peer, 404, "not found")
            if (headers["x-sync-code"] != code) {
                android.util.Log.d("LTSync", "reject ${peer.inetAddress?.hostAddress}: code='${headers["x-sync-code"]}' expected='$code'")
                return respond(peer, 403, "wrong pairing code")
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length <= 0 || length > MAX_BODY_BYTES) return respond(peer, 413, "bad body")
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(body, read, length - read)
                if (count <= 0) break
                read += count
            }
            val payload = runCatching { SyncJson.decode(String(body, 0, read, Charsets.UTF_8)) }.getOrNull()
                ?: return respond(peer, 400, "bad json")
            val summary = runCatching { runBlocking { onPush(payload) } }
                .getOrElse { return respond(peer, 500, it.message ?: "merge failed") }
            respond(peer, 200, JSONObject().put("ok", true).put("summary", summary).toString())
        }
    }

    private fun respond(
        peer: java.net.Socket,
        status: Int,
        body: String,
    ) {
        runCatching {
            val bytes = body.toByteArray()
            val output = BufferedOutputStream(peer.getOutputStream())
            output.write(
                (
                    "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                        "Content-Type: application/json; charset=utf-8\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                ).toByteArray(),
            )
            output.write(bytes)
            output.flush()
        }
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (buffer.isEmpty()) null else buffer.toString()
            if (byte == '\n'.code) return buffer.toString().trimEnd('\r')
            buffer.append(byte.toChar())
            if (buffer.length > MAX_LINE) return null
        }
    }

    private companion object {
        const val REQUEST_TIMEOUT_MS = 20_000
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
        const val MAX_LINE = 8 * 1024
    }
}

@Singleton
class SyncClient
    @Inject
    constructor() {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    suspend fun push(
        host: String,
        code: String,
        payload: SyncPayload,
    ): String =
        withContext(Dispatchers.IO) {
            val url = "http://${host.trim()}:$SYNC_PORT/push"
            val body = SyncJson.encode(payload).toRequestBody("application/json".toMediaType())
            val request =
                Request
                    .Builder()
                    .url(url)
                    .header("X-Sync-Code", code.trim())
                    .post(body)
                    .build()
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    error(
                        when (response.code) {
                            403 -> "配对码不正确"
                            else -> text.ifBlank { "HTTP ${response.code}" }
                        },
                    )
                }
                runCatching { JSONObject(text).optString("summary") }.getOrNull().orEmpty()
            }
        }
}

/** IPv4 addresses this device answers on, for the pairing screen. */
fun localIpv4Addresses(): List<String> =
    runCatching {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
    }.getOrDefault(emptyList())
