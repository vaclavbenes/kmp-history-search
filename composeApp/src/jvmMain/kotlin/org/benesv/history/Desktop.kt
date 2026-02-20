package org.benesv.history

import org.benesv.history.core.Log
import java.awt.Desktop

object Desktop {
    fun requestForeground() {
        // delay(50) // wait for a window to be visible, maybe compose bug
        Log.i("Put Application to foreground")
        Desktop.getDesktop().requestForeground(true)
    }

}
