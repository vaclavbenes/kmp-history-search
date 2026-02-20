package org.benesv.history.core

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.StackWalker
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KClass

object Log {

    private val walker: StackWalker =
        StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE)

    private val loggerCache = ConcurrentHashMap<String, KLogger>()

    private fun loggerFor(owner: KClass<*>?) =
        if (owner != null) KotlinLogging.logger(owner.qualifiedName ?: owner.simpleName ?: "Log")
        else KotlinLogging.logger("Log")

    private fun callerLogger(): KLogger {
        val callerClass: Class<*> = walker.walk { frames ->
            frames
                .map { it.declaringClass }
                .filter { cls ->
                    cls != Log::class.java &&
                        cls.name != "org.benesv.history.core.LogKt" &&
                        !cls.name.startsWith("java.") &&
                        !cls.name.startsWith("jdk.") &&
                        !cls.name.startsWith("sun.") &&
                        !cls.name.startsWith("kotlin.")
                }
                .findFirst()
                .orElse(Log::class.java)
        }

        val name = if (callerClass == Log::class.java) "Log" else callerClass.name
        return loggerCache.getOrPut(name) { KotlinLogging.logger(name) }
    }


    fun d(message: String, owner: KClass<*>? = null) {
        try {
            val log = owner?.let { loggerFor(it) } ?: callerLogger()
            log.debug { message }
        } catch (e: Exception) {
            println(message)
        }
    }

    fun i(message: String, owner: KClass<*>? = null) {
        try {
            val log = owner?.let { loggerFor(it) } ?: callerLogger()
            log.info { message }
        } catch (e: Exception) {
            println(message)
        }
    }

    fun w(message: String, owner: KClass<*>? = null) {
        try {
            val log = owner?.let { loggerFor(it) } ?: callerLogger()
            log.warn { message }
        } catch (e: Exception) {
            System.err.println(message)
        }
    }

    fun e(message: String, owner: KClass<*>? = null) {
        try {
            val log = owner?.let { loggerFor(it) } ?: callerLogger()
            log.error { message }
        } catch (e: Exception) {
            System.err.println(message)
        }
    }
}
