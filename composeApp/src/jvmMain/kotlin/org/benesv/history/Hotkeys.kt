package org.benesv.history

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import org.benesv.history.core.Log
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Minimal global hotkey manager using JNativeHook.
 * Registers Ctrl+Shift+H (and Cmd+Shift+H on macOS) to trigger [onShow].
 */
object HotkeysManager {
    private var registered = false
    private var listener: NativeKeyListener? = null

    fun start(onShow: () -> Unit) {
        if (registered) return
        try {
            val logger = Logger.getLogger("com.github.kwhat.jnativehook")
            logger.level = Level.WARNING
            logger.useParentHandlers = false

            GlobalScreen.registerNativeHook()
            val l = object : NativeKeyListener {
                override fun nativeKeyPressed(e: NativeKeyEvent) {
                    Log.i("Key pressed: ${e.keyChar}")
                    val isB = e.keyCode == NativeKeyEvent.VC_B
                    val alt = e.modifiers and NativeKeyEvent.ALT_MASK != 0
                    val meta = e.modifiers and NativeKeyEvent.META_MASK != 0 // macOS Command
                    if ((meta || alt) && isB) {
                        onShow()
                    }
                }
            }
            GlobalScreen.addNativeKeyListener(l)
            listener = l
            registered = true
        } catch (ex: NativeHookException) {
            Log.w("Failed to register global hook: ${ex.message}")
        } catch (t: Throwable) {
            Log.w("Global hotkeys disabled due to runtime error: ${t::class.simpleName}: ${t.message}")
        }
    }

    fun stop() {
        if (!registered) return
        try {
            listener?.let { GlobalScreen.removeNativeKeyListener(it) }
            listener = null
            GlobalScreen.unregisterNativeHook()
        } catch (_: Exception) {
            // ignore
        } finally {
            registered = false
        }
    }
}
