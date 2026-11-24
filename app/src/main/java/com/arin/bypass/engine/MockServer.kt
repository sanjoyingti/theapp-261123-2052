package com.arin.bypass.engine

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Tiny loopback HTTP server for trying a patched app against hand written answers.
 * Start and stop are cheap and nothing is bound until the first start.
 */
object MockServer {

    const val PORT = 8099

    @Volatile
    private var server: ServerSocket? = null

    private val routes = LinkedHashMap<String, String>()

    val running: Boolean get() = server?.isClosed == false

    fun baseUrl(): String = "http://127.0.0.1:$PORT"

    fun routes(): List<Pair<String, String>> = synchronized(routes) { routes.toList() }

    fun put(path: String, body: String) {
        synchronized(routes) { routes[normalise(path)] = body }
    }

    fun remove(path: String) {
        synchronized(routes) { routes.remove(normalise(path)) }
    }

    fun start(): Result<Int> {
        if (running) return Result.success(PORT)
        return runCatching {
            val socket = ServerSocket(PORT)
            server = socket
            val worker = thread(isDaemon = true, name = "bypass-mock-server") {
                while (!socket.isClosed) {
                    runCatching { socket.accept().use { answer(it) } }
                }
            }
            worker.name
            PORT
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun answer(client: Socket) {
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val requestLine = reader.readLine() ?: return
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val path = normalise(requestLine.split(" ").getOrElse(1) { "/" }.substringBefore('?'))
        val body = synchronized(routes) { routes[path] }
            ?: """{"bypass":true,"path":"$path"}"""
        val payload = body.toByteArray(Charsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        client.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(payload)
            flush()
        }
    }

    private fun normalise(path: String): String = "/" + path.trim().trimStart('/')
}
