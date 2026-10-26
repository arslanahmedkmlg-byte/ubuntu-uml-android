package com.eaglenet.ubuntuuml

import org.junit.Assert.assertEquals
import org.junit.Test

class PtyWindowSizeTest {
    @Test
    fun clampsEmptyDimensionsAndPreservesColumnRowOrdering() {
        assertEquals(PtyWindowSize(1, 1), ptyWindowSize(0, 0))
        assertEquals(PtyWindowSize(132, 43), ptyWindowSize(132, 43))
    }
}
