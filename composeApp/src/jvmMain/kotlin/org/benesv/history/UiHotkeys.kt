package org.benesv.history

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Application-only hotkeys utilities for in-app controls (not global OS hooks).
 * Centralizes key combination definitions and text manipulation helpers.
 */
object UiHotkeys {
    // Predicate helpers ------------------------------------------------------
    fun isKeyDown(e: KeyEvent) = e.type == KeyEventType.KeyDown

    // Text field actions
    fun isAcceptSuggestion(e: KeyEvent): Boolean = isKeyDown(e) && e.key == Key.Tab
    fun isPrevWord(e: KeyEvent): Boolean = isKeyDown(e) && e.isAltPressed && e.key == Key.DirectionLeft
    fun isNextWord(e: KeyEvent): Boolean = isKeyDown(e) && e.isAltPressed && e.key == Key.DirectionRight
    fun isDeletePrevWord(e: KeyEvent): Boolean = isKeyDown(e) && e.isCtrlPressed && e.key == Key.W
    fun isClearLine(e: KeyEvent): Boolean = isKeyDown(e) && e.isShiftPressed && e.isAltPressed && e.key == Key.K

    // Suggestions cycling with Alt+Up/Down
    fun isCycleSuggestionDown(e: KeyEvent): Boolean = isKeyDown(e) && e.isAltPressed && e.key == Key.DirectionDown
    fun isCycleSuggestionUp(e: KeyEvent): Boolean = isKeyDown(e) && e.isAltPressed && e.key == Key.DirectionUp

    // History list navigation (plain Up/Down)
    fun isListDown(e: KeyEvent): Boolean = isKeyDown(e) && !e.isAltPressed && e.key == Key.DirectionDown
    fun isListUp(e: KeyEvent): Boolean = isKeyDown(e) && !e.isAltPressed && e.key == Key.DirectionUp
    fun isActivate(e: KeyEvent): Boolean = isKeyDown(e) && e.key == Key.Enter

    // Text helpers -----------------------------------------------------------
    private fun Char.isWordChar(): Boolean = this.isLetterOrDigit()

    private fun prevWordStart(text: String, cursor: Int): Int {
        var i = cursor
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && text[i - 1].isWordChar()) i--
        return i
    }

    private fun nextWordEnd(text: String, cursor: Int): Int {
        var i = cursor
        val n = text.length
        while (i < n && text[i].isWhitespace()) i++
        while (i < n && text[i].isWordChar()) i++
        return i
    }

    fun movePrevWord(value: TextFieldValue): TextFieldValue {
        val cur = value.selection.end
        val newPos = prevWordStart(value.text, cur)
        return value.copy(selection = TextRange(newPos))
    }

    fun moveNextWord(value: TextFieldValue): TextFieldValue {
        val cur = value.selection.end
        val newPos = nextWordEnd(value.text, cur)
        return value.copy(selection = TextRange(newPos))
    }

    fun deletePrevWord(value: TextFieldValue): TextFieldValue {
        val cur = value.selection.end
        val start = prevWordStart(value.text, cur)
        val newText = buildString {
            append(value.text.substring(0, start))
            append(value.text.substring(cur))
        }
        return value.copy(text = newText, selection = TextRange(start))
    }

    fun clearLine(): TextFieldValue = TextFieldValue("")
}
