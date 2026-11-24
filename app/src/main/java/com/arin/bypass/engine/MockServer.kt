package com.arin.bypass.engine

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * Tiny loopback HTTP server used when a patched base URL points back at the phone.
 * It answers every request with a small JSON body and keeps the last 200 requests so
 * the log view can show what the clone actually asked for.
 */
class MockServer {

    private val lines: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Volatile
    private var socket: ServerSocket? = null

    @Volatile
    private var worker: Thread? = null

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    fun log(): List<String> = synchronized(lines) { lines.toList() }

    fun start(port: Int): Result<Unit> = runCatching {
        if (running) {
            add("Already listening on http://$HOST:$this.port")
            return@runCatching
        }
        val opened = ServerSocket(port, 32, InetAddress.getByName(HOST))
        socket = opened
        this.port = opened.localPort
        running = true
        add("Listening on http://$HOST:${opened.localPort}")
        val thread = Thread { loop(opened) }
        thread.isDaemon = true
        thread.start()
        worker = thread
    }

    fun stop() {
        val opened = socket
        running = false
        socket = null
        worker = null
        runCatching { opened?.close() }
        add("Stopped")
    }

    private fun loop(opened: ServerSocket) {
        while (running) {
            val client = runCatching { opened.accept() }.getOrNull() ?: break
            runCatching { serve(client) }
                .onFailure { add("Request failed: ${it.message ?: it::class.java.simpleName}") }
        }
        running = false
    }

    private fun serve(client: Socket) {
        client.use { open ->
            open.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(open.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            var guard = 0
            while (guard < 100) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                guard++
            }
            add("${requestLine.substringBefore(' ')} $path")
            val body = "{\"ok\":true,\"mock\":true,\"path\":\"${escape(path)}\"}"
            val bytes = body.toByteArray(Charsets.UTF_8)
            val head = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: application/json; charset=utf-8\r\n")
                append("Content-Length: ").append(bytes.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = open.getOutputStream()
            out.write(head.toByteArray(Charsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun add(line: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        synchronized(lines) {
            lines.add("$stamp  $line")
            while (lines.size > 200) lines.removeAt(0)
        }
    }

    private fun escape(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
