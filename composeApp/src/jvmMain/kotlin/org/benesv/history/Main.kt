package org.benesv.history

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import org.benesv.history.BuildConfig.DEVELOP
import org.benesv.history.ui.BrowserHistoryIconPainter


fun main() {

    application {
        var showWindow by remember { mutableStateOf(DEVELOP) }

        val trayIconPainter = remember(showWindow) {
            BrowserHistoryIconPainter(
                iconColor = if (showWindow) Color.White else Color.Gray
            )
        }

        DisposableEffect(Unit) {
            HotkeysManager.start { showWindow = !showWindow }
            onDispose { HotkeysManager.stop() }
        }

        Tray(
            icon = trayIconPainter,
            state = rememberTrayState(),
            tooltip = "History Search",
            onAction = { showWindow = true },
            menu = {
                Item("Toggle", onClick = { showWindow = !showWindow })
                Separator()
                Item("Quit", onClick = ::exitApplication)
            }
        )

        if (showWindow) {
            Window(
                onCloseRequest = { showWindow = false },
                title = "kmp-history-search",
                alwaysOnTop = true,
                state = androidx.compose.ui.window.rememberWindowState(width = 1200.dp, height = 800.dp),
            ) {
                App()
                LaunchedEffect(showWindow) {
                    if (showWindow) {
                        Desktop.requestForeground()
                    }
                }
            }
        }
    }
}
