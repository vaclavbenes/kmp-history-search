package org.benesv.history

import kotlinx.coroutines.delay
import org.benesv.history.core.Log
import java.awt.Desktop
import java.net.URI

object Desktop {
    private val desktop = Desktop.getDesktop()

    private val DELAY = 30L

    suspend fun requestForeground() {
        delay(DELAY) // wait for a window to be visible, maybe compose/awt bug
        Log.i("Put Application to foreground with ${DELAY}ms delay")
        desktop.requestForeground(true)
    }

    fun getDesktop(url: String){
        desktop.browse(URI(url))
    }

}
