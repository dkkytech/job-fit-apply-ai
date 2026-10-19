package com.jd.jobbot.support

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList

/** A tiny scripted HTTP server: route by "METHOD path" prefix, record every request. */
class FakeHttp : AutoCloseable {
    data class Req(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    data class Resp(val code: Int, val body: String)

    val requests = CopyOnWriteArrayList<Req>()
    private val routes = mutableListOf<Pair<String, (Req) -> Resp>>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.createContext("/") { ex: HttpExchange ->
            val req = Req(
                ex.requestMethod,
                ex.requestURI.toString(),
                ex.requestHeaders.entries.associate { it.key.lowercase() to it.value.first() },
                ex.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            requests += req
            val handler = routes.lastOrNull { (prefix, _) -> "${req.method} ${req.path}".startsWith(prefix) }?.second
            val resp = handler?.invoke(req) ?: Resp(404, """{"error":"no route"}""")
            val bytes = resp.body.toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(resp.code, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) ex.responseBody.use { it.write(bytes) } else ex.close()
        }
        server.start()
    }

    fun on(prefix: String, handler: (Req) -> Resp) { routes += prefix to handler }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
