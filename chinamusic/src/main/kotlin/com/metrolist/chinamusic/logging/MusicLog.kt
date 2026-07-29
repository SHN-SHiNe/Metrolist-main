package com.metrolist.chinamusic.logging

import java.util.logging.Level
import java.util.logging.Logger

/**
 * Small JVM-safe logging facade used by the reusable music-source module.
 * It intentionally mirrors the subset of Timber used by this module so the
 * same source can run in Android and on the NAS without an Android dependency.
 */
object MusicLog {
    fun tag(tag: String): TaggedLogger = TaggedLogger(Logger.getLogger(tag))
}

class TaggedLogger internal constructor(private val logger: Logger) {
    fun d(message: String, vararg args: Any?) = log(Level.FINE, null, message, args)
    fun d(error: Throwable, message: String, vararg args: Any?) = log(Level.FINE, error, message, args)
    fun i(message: String, vararg args: Any?) = log(Level.INFO, null, message, args)
    fun i(error: Throwable, message: String, vararg args: Any?) = log(Level.INFO, error, message, args)
    fun w(message: String, vararg args: Any?) = log(Level.WARNING, null, message, args)
    fun w(error: Throwable, message: String, vararg args: Any?) = log(Level.WARNING, error, message, args)
    fun e(message: String, vararg args: Any?) = log(Level.SEVERE, null, message, args)
    fun e(error: Throwable, message: String, vararg args: Any?) = log(Level.SEVERE, error, message, args)

    private fun log(level: Level, error: Throwable?, message: String, args: Array<out Any?>) {
        if (!logger.isLoggable(level)) return
        val rendered = if (args.isEmpty()) message else runCatching { message.format(*args) }.getOrDefault(message)
        logger.log(level, rendered, error)
    }
}
