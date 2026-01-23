package org.benesv.history

import com.github.kwhat.jnativehook.GlobalScreen
import com.github.kwhat.jnativehook.NativeHookException
import com.github.kwhat.jnativehook.keyboard.NativeKeyEvent
import com.github.kwhat.jnativehook.keyboard.NativeKeyListener
import org.benesv.history.core.Log
import java.awt.EventQueue
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
                    // Prefer keyCode/modifiers over keyChar for diagnostics.
                    Log.i("Key pressed: code=${e.keyCode}, modifiers=${e.modifiers} , rawCode=${e.rawCode}")

                    val isB = e.keyCode == NativeKeyEvent.VC_B
                    val shift = e.modifiers and NativeKeyEvent.SHIFT_MASK != 0
                    val ctrl = e.modifiers and NativeKeyEvent.CTRL_MASK != 0
                    val cmd = e.modifiers and NativeKeyEvent.META_MASK != 0 // macOS Command
                    val alt = e.modifiers and NativeKeyEvent.ALT_MASK != 0

                    val matches = isB && cmd
                    if (matches) {
                        // JNativeHook callback is not on the Compose/UI thread.
                        EventQueue.invokeLater {
                            onShow()
                        }
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
