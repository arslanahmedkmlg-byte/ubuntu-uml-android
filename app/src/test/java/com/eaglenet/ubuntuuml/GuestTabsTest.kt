package com.eaglenet.ubuntuuml

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestTabsTest {
    @Test fun parsesActiveGuestWindowsWithoutGuessingTabs() {
        val tabs = parseGuestTabs("UMLTABS|0~shell~0;2~nano~1;5~logs~0;")
        assertEquals(listOf(GuestTab(0, "shell", false), GuestTab(2, "nano", true), GuestTab(5, "logs", false)), tabs)
    }

    @Test fun ignoresUntrustedOrUnrelatedTitles() {
        assertTrue(parseGuestTabs("vim - editing").isEmpty())
        assertEquals(listOf(GuestTab(1, "Shell 1", true)), parseGuestTabs("UMLTABS|bad;1~~1;9999~oops~1;"))
    }

    @Test fun selectsWindowUsingTmuxCommandMode() {
        assertArrayEquals("\u0002:select-window -t :12\r".toByteArray(), selectGuestTabInput(12))
    }
}
