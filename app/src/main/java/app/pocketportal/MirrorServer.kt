package app.pocketportal

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A tiny HTTP + WebSocket server: `GET /` serves the viewer page, `GET /ws?k=PIN`
 * upgrades to a WebSocket that carries JPEG frames to the headset and touch
 * events back.
 *
 * Frames are sent latest-wins with at most [MAX_IN_FLIGHT] unacknowledged, so a
 * slow network drops frames instead of building up seconds of lag.
 */
class MirrorServer(
    private val port: Int,
    private val page: ByteArray,
    private val pin: () -> String,
    private val onConnected: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onViewersChanged: (Int) -> Unit,
) {
    private var serverSocket: ServerSocket? = null
    private val clients = CopyOnWriteArrayList<Client>()
    private val pool = Executors.newCachedThreadPool()

    val hasViewers: Boolean get() = clients.isNotEmpty()

    @Throws(IOException::class)
    fun start() {
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
        }
        serverSocket = socket
        thread(name = "pocketportal-accept", isDaemon = true) {
            while (!socket.isClosed) {
                val connection = try {
                    socket.accept()
                } catch (e: IOException) {
                    break
                }
                pool.execute { handle(connection) }
            }
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        clients.forEach { it.close() }
        pool.shutdownNow()
    }

    fun broadcastFrame(jpeg: ByteArray) = clients.forEach { it.offerFrame(jpeg) }

    fun broadcastText(text: String) = clients.forEach { it.offerText(text) }

    fun broadcastAudio(chunk: ByteArray) = clients.forEach { it.offerAudio(chunk) }

    private fun handle(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val out = socket.getOutputStream()
            val request = readHeaders(input) ?: return socket.close()
            val (path, query) = request.target.split('?', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            when {
                path == "/ws" && request.headers["upgrade"].equals("websocket", ignoreCase = true) ->
                    serveWebSocket(socket, input, out, request, parseQuery(query))
                path == "/" || path == "/index.html" -> respond(out, "200 OK", "text/html; charset=utf-8", page)
                path == "/favicon.ico" -> respond(out, "204 No Content", "text/plain", ByteArray(0))
                else -> respond(out, "404 Not Found", "text/plain", "Not found".toByteArray())
            }
        } catch (e: IOException) {
            Log.d(TAG, "connection ended: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serveWebSocket(
        socket: Socket,
        input: BufferedInputStream,
        out: OutputStream,
        request: Request,
        query: Map<String, String>,
    ) {
        val key = request.headers["sec-websocket-key"] ?: return
        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray())
        )
        out.write(
            ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray()
        )
        out.flush()

        val client = Client(socket, out)
        val given = query["k"].orEmpty().toByteArray()
        if (!MessageDigest.isEqual(given, pin().toByteArray())) {
            // Slow down guessing, then tell the page why it was refused. Wait for the
            // browser's close reply: closing with its bytes unread sends a TCP reset,
            // and browsers then drop the 4001 code and report a plain network error.
            Thread.sleep(1000)
            client.sendClose(4001, "Wrong PIN")
            socket.soTimeout = 2000
            runCatching { client.readerLoop(DataInputStream(input)) }
            return
        }

        socket.soTimeout = 30_000 // the page pings every 10 s
        clients.add(client)
        onViewersChanged(clients.size)
        pool.execute { client.writerLoop() }
        onConnected()
        try {
            client.readerLoop(DataInputStream(input))
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "viewer timed out")
        } catch (e: IOException) {
            Log.d(TAG, "viewer left: ${e.message}")
        } finally {
            client.close()
            clients.remove(client)
            onViewersChanged(clients.size)
        }
    }

    private inner class Client(private val socket: Socket, private val out: OutputStream) {
        private val lock = Object()
        private var frame: ByteArray? = null
        private val texts = ArrayDeque<String>()
        // Audio isn't latest-wins like video: every chunk plays, but a slow link
        // drops the oldest so sound never falls more than MAX_AUDIO_QUEUE behind.
        private val audio = ArrayDeque<ByteArray>()
        private var inFlight = 0
        @Volatile private var open = true
        @Volatile private var closeSent = false
        /** The viewer's mute button turns this off. */
        @Volatile var wantsAudio = true

        fun offerFrame(jpeg: ByteArray) = synchronized(lock) {
            frame = jpeg
            lock.notifyAll()
        }

        fun offerText(text: String) = synchronized(lock) {
            texts.addLast(text)
            lock.notifyAll()
        }

        fun offerAudio(chunk: ByteArray) {
            if (!wantsAudio) return
            synchronized(lock) {
                audio.addLast(chunk)
                while (audio.size > MAX_AUDIO_QUEUE) audio.removeFirst()
                lock.notifyAll()
            }
        }

        fun writerLoop() {
            try {
                while (open) {
                    var text: String? = null
                    var chunk: ByteArray? = null
                    var jpeg: ByteArray? = null
                    synchronized(lock) {
                        while (open && texts.isEmpty() && audio.isEmpty() &&
                            (frame == null || inFlight >= MAX_IN_FLIGHT)
                        ) lock.wait()
                        if (!open) return
                        when {
                            texts.isNotEmpty() -> text = texts.removeFirst()
                            // Sound gaps are more noticeable than a late frame.
                            audio.isNotEmpty() -> chunk = audio.removeFirst()
                            else -> {
                                jpeg = frame
                                frame = null
                                inFlight++
                            }
                        }
                    }
                    text?.let { send(OP_TEXT, it.toByteArray()) }
                    chunk?.let { send(OP_BINARY, it) }
                    jpeg?.let { send(OP_BINARY, it) }
                }
            } catch (e: Exception) {
                close()
            }
        }

        fun readerLoop(input: DataInputStream) {
            while (open) {
                val b0 = input.read()
                if (b0 < 0) return
                val b1 = input.readUnsignedByte()
                val opcode = b0 and 0x0F
                var length = (b1 and 0x7F).toLong()
                if (length == 126L) length = input.readUnsignedShort().toLong()
                else if (length == 127L) length = input.readLong()
                if (length > MAX_MESSAGE) return
                val mask = if (b1 and 0x80 != 0) ByteArray(4).also { input.readFully(it) } else null
                val payload = ByteArray(length.toInt()).also { input.readFully(it) }
                if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                when (opcode) {
                    OP_TEXT -> {
                        val text = String(payload)
                        when (text) {
                            "a" -> synchronized(lock) {
                                if (inFlight > 0) inFlight--
                                lock.notifyAll()
                            }
                            "p" -> Unit
                            AUDIO_ON -> wantsAudio = true
                            AUDIO_OFF -> wantsAudio = false
                            else -> runCatching { onMessage(text) }.onFailure { Log.w(TAG, "bad message", it) }
                        }
                    }
                    OP_CLOSE -> {
                        if (!closeSent) sendClose(1000, "")
                        return
                    }
                    OP_PING -> send(OP_PONG, payload)
                }
            }
        }

        fun sendClose(code: Int, reason: String) {
            val body = byteArrayOf((code shr 8).toByte(), code.toByte()) + reason.toByteArray()
            closeSent = true
            runCatching { send(OP_CLOSE, body) }
        }

        private fun send(opcode: Int, payload: ByteArray) {
            val header = ByteArrayOutputStream(10)
            header.write(0x80 or opcode)
            when {
                payload.size < 126 -> header.write(payload.size)
                payload.size < 65536 -> {
                    header.write(126)
                    header.write(payload.size shr 8)
                    header.write(payload.size)
                }
                else -> {
                    header.write(127)
                    for (shift in 56 downTo 0 step 8) header.write((payload.size.toLong() shr shift).toInt())
                }
            }
            synchronized(out) {
                out.write(header.toByteArray())
                out.write(payload)
                out.flush()
            }
        }

        fun close() {
            open = false
            synchronized(lock) { lock.notifyAll() }
            runCatching { socket.close() }
        }
    }

    private class Request(val target: String, val headers: Map<String, String>)

    private fun readHeaders(input: BufferedInputStream): Request? {
        val buffer = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || buffer.size() > 16_384) return null
            buffer.write(b)
            matched = when {
                (matched == 0 || matched == 2) && b == '\r'.code -> matched + 1
                (matched == 1 || matched == 3) && b == '\n'.code -> matched + 1
                b == '\r'.code -> 1
                else -> 0
            }
        }
        val lines = buffer.toString(Charsets.ISO_8859_1.name()).split("\r\n")
        val parts = lines.first().split(' ')
        if (parts.size < 2 || parts[0] != "GET") return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        return Request(parts[1], headers)
    }

    private fun parseQuery(query: String): Map<String, String> = query.split('&')
        .filter { it.isNotEmpty() }
        .associate {
            val (k, v) = it.split('=', limit = 2).let { p -> p[0] to p.getOrElse(1) { "" } }
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }

    private fun respond(out: OutputStream, status: String, type: String, body: ByteArray) {
        out.write(
            ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
        )
        out.write(body)
        out.flush()
    }

    private companion object {
        const val TAG = "MirrorServer"
        const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        const val MAX_IN_FLIGHT = 2
        // 40 ms chunks: half a second of sound at most.
        const val MAX_AUDIO_QUEUE = 12
        const val AUDIO_ON = """{"t":"audio","on":true}"""
        const val AUDIO_OFF = """{"t":"audio","on":false}"""
        const val MAX_MESSAGE = 1L shl 20
        const val OP_TEXT = 0x1
        const val OP_BINARY = 0x2
        const val OP_CLOSE = 0x8
        const val OP_PING = 0x9
        const val OP_PONG = 0xA
    }
}
