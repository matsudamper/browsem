package net.matsudamper.browser.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TabInsertionPolicyTest {

    @Test
    fun openerTabIdがnullの場合は末尾に追加する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(tabIds, openerTabId = null)

        assertEquals(3, result)
    }

    @Test
    fun openerTabIdがリストに存在する場合はその次に挿入する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(tabIds, openerTabId = "b")

        assertEquals(2, result)
    }

    @Test
    fun openerTabIdが先頭タブの場合は2番目に挿入する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(tabIds, openerTabId = "a")

        assertEquals(1, result)
    }

    @Test
    fun openerTabIdが末尾タブの場合は末尾に挿入する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(tabIds, openerTabId = "c")

        assertEquals(3, result)
    }

    @Test
    fun openerTabIdがリストに存在しない場合は末尾に追加する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(tabIds, openerTabId = "x")

        assertEquals(3, result)
    }

    @Test
    fun タブリストが空の場合は0を返す() {
        val result = TabInsertionPolicy.resolveInsertionIndex(emptyList(), openerTabId = "a")

        assertEquals(0, result)
    }

    @Test
    fun タブリストが空かつopenerTabIdがnullの場合は0を返す() {
        val result = TabInsertionPolicy.resolveInsertionIndex(emptyList(), openerTabId = null)

        assertEquals(0, result)
    }

    @Test
    fun selectedTabIdが指定されている場合はその次に挿入する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(
            tabIds,
            openerTabId = null,
            selectedTabId = "b",
        )

        assertEquals(2, result)
    }

    @Test
    fun selectedTabIdが先頭タブの場合は2番目に挿入する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(
            tabIds,
            openerTabId = null,
            selectedTabId = "a",
        )

        assertEquals(1, result)
    }

    @Test
    fun selectedTabIdが末尾タブの場合は末尾に挿入する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(
            tabIds,
            openerTabId = null,
            selectedTabId = "c",
        )

        assertEquals(3, result)
    }

    @Test
    fun selectedTabIdがリストに存在しない場合は末尾に追加する() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(
            tabIds,
            openerTabId = null,
            selectedTabId = "x",
        )

        assertEquals(3, result)
    }

    @Test
    fun openerTabIdとselectedTabIdの両方が指定されている場合はopenerTabIdが優先される() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(
            tabIds,
            openerTabId = "a",
            selectedTabId = "c",
        )

        assertEquals(1, result)
    }

    @Test
    fun openerTabIdが存在しない場合はselectedTabIdにフォールバックする() {
        val tabIds = listOf("a", "b", "c")

        val result = TabInsertionPolicy.resolveInsertionIndex(
            tabIds,
            openerTabId = "x",
            selectedTabId = "b",
        )

        assertEquals(2, result)
    }
}
