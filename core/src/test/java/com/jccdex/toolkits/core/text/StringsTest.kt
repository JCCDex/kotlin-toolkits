package com.jccdex.toolkits.core.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StringsTest {
    @Test
    fun `notBlankOrNull returns null for null blank or whitespace-only`() {
        assertNull((null as String?).notBlankOrNull())
        assertNull("".notBlankOrNull())
        assertNull("   ".notBlankOrNull())
        assertNull("\t\n ".notBlankOrNull())
    }

    @Test
    fun `notBlankOrNull keeps non-blank strings as-is`() {
        assertEquals("a", "a".notBlankOrNull())
        assertEquals(" a ", " a ".notBlankOrNull())
    }
}
