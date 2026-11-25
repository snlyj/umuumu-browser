package com.yuka.cutebrowser

import com.yuka.cutebrowser.ui.SheetType
import com.yuka.cutebrowser.ui.sheetAllowsSwipeDismiss
import com.yuka.cutebrowser.ui.sheetOnBack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v1.6.2：设置/安全页不能被下滑或惯性滑动关掉 */
class SheetDismissTest {
    @Test
    fun settingsFlowBlocksSwipeDismiss() {
        assertFalse(sheetAllowsSwipeDismiss(SheetType.Settings))
        assertFalse(sheetAllowsSwipeDismiss(SheetType.Security))
        assertFalse(sheetAllowsSwipeDismiss(SheetType.SitePerms))
        assertTrue(sheetAllowsSwipeDismiss(SheetType.Menu))
        assertTrue(sheetAllowsSwipeDismiss(SheetType.Tabs))
        assertTrue(sheetAllowsSwipeDismiss(SheetType.Bookmarks))
        assertTrue(sheetAllowsSwipeDismiss(SheetType.History))
        assertTrue(sheetAllowsSwipeDismiss(SheetType.Downloads))
    }

    @Test
    fun backPopsSettingsThenCloses() {
        assertEquals(SheetType.Settings, sheetOnBack(SheetType.Security))
        assertEquals(SheetType.Security, sheetOnBack(SheetType.SitePerms))
        assertEquals(SheetType.None, sheetOnBack(SheetType.Settings))
        assertEquals(SheetType.None, sheetOnBack(SheetType.Menu))
        assertEquals(SheetType.None, sheetOnBack(SheetType.None))
    }
}
