package com.shine.music.server

import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val port = System.getenv("SHINE_HTTP_PORT")?.toIntOrNull() ?: 8767
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        shineModule()
    }.start(wait = true)
}
