package net.matsudamper.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExtensionActionOrderTest {

    @Test
    fun originalOrderIsKeptWhenSavedOrderIsEmpty() {
        assertEquals(
            listOf("a", "b", "c"),
            sortByExtensionActionOrder(listOf("a", "b", "c"), emptyList()) { it },
        )
    }

    @Test
    fun itemsAreSortedBySavedOrder() {
        assertEquals(
            listOf("c", "a", "b"),
            sortByExtensionActionOrder(listOf("a", "b", "c"), listOf("c", "a", "b")) { it },
        )
    }

    @Test
    fun unknownExtensionsKeepOriginalOrderAtTail() {
        assertEquals(
            listOf("c", "a", "d", "e"),
            sortByExtensionActionOrder(listOf("d", "a", "e", "c"), listOf("c", "a")) { it },
        )
    }

    @Test
    fun savedOrderForUninstalledExtensionIsIgnored() {
        assertEquals(
            listOf("b", "a"),
            sortByExtensionActionOrder(listOf("a", "b"), listOf("z", "b", "a")) { it },
        )
    }

    @Test
    fun visibleOrderIsMergedKeepingHiddenExtensionPositions() {
        assertEquals(
            listOf("c", "b", "a", "d"),
            mergeVisibleExtensionActionOrder(
                savedOrder = listOf("a", "b", "c", "d"),
                visibleOrder = listOf("c", "a"),
            ),
        )
    }

    @Test
    fun newExtensionIsAppendedWhenMergingVisibleOrder() {
        assertEquals(
            listOf("b", "a", "new"),
            mergeVisibleExtensionActionOrder(
                savedOrder = listOf("a", "b"),
                visibleOrder = listOf("b", "a", "new"),
            ),
        )
    }

    @Test
    fun itemIsMovedToTheRight() {
        assertEquals(
            listOf("b", "a", "c"),
            moveExtensionActionOrder(listOf("a", "b", "c"), fromIndex = 0, toIndex = 1),
        )
    }

    @Test
    fun itemIsMovedToTheLeft() {
        assertEquals(
            listOf("c", "a", "b"),
            moveExtensionActionOrder(listOf("a", "b", "c"), fromIndex = 2, toIndex = 0),
        )
    }

    @Test
    fun moveToSameIndexReturnsNull() {
        assertNull(moveExtensionActionOrder(listOf("a", "b"), fromIndex = 1, toIndex = 1))
    }

    @Test
    fun moveOutOfRangeReturnsNull() {
        assertNull(moveExtensionActionOrder(listOf("a", "b"), fromIndex = 0, toIndex = 2))
        assertNull(moveExtensionActionOrder(listOf("a", "b"), fromIndex = -1, toIndex = 0))
    }
}
