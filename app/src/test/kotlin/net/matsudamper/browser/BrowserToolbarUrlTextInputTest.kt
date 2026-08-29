package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class BrowserToolbarUrlTextInputTest {
    @Test
    fun cursorOffsetIsLimitedByStaleShorterLayoutDuringVoiceInput() {
        assertEquals(
            3,
            cursorOffsetForLayout(
                selectionEnd = 8,
                textLength = 8,
                layoutTextLength = 3,
            ),
        )
    }

    @Test
    fun cursorOffsetIsLimitedByCurrentTextAfterVoiceInputReplacement() {
        assertEquals(
            2,
            cursorOffsetForLayout(
                selectionEnd = 8,
                textLength = 2,
                layoutTextLength = 8,
            ),
        )
    }

    @Test
    fun negativeCursorOffsetIsNormalizedToStart() {
        assertEquals(
            0,
            cursorOffsetForLayout(
                selectionEnd = -1,
                textLength = 3,
                layoutTextLength = 3,
            ),
        )
    }
}
