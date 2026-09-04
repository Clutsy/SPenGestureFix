package com.spengesturefix

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-logic tests for the wheel color text parser: hex (3/6/8 digits, with
 * or without #/0x) and decimal RGB triples must all work; garbage must fail
 * cleanly without crashing the dashboard.
 */
class WheelColorInputTest {

    @Test
    fun `parses six digit hex with hash`() {
        assertEquals(Color(0xFF29B6F6), parseWheelColorInput("#29B6F6"))
        assertEquals(Color(0xFFFF8800), parseWheelColorInput("#FF8800"))
    }

    @Test
    fun `parses hex without hash and with 0x prefix`() {
        assertEquals(Color(0xFF29B6F6), parseWheelColorInput("29B6F6"))
        assertEquals(Color(0xFF29B6F6), parseWheelColorInput("0x29B6F6"))
        assertEquals(Color(0xFF29B6F6), parseWheelColorInput("0X29b6f6"))
    }

    @Test
    fun `parses three digit shorthand hex`() {
        assertEquals(Color(0xFFFF0000), parseWheelColorInput("#F00"))
        assertEquals(Color(0xFF00FF00), parseWheelColorInput("0F0"))
    }

    @Test
    fun `parses eight digit hex with alpha`() {
        assertEquals(Color(0x80FF8800.toInt()), parseWheelColorInput("#80FF8800"))
    }

    @Test
    fun `parses decimal rgb triples with several separators`() {
        assertEquals(Color(0xFF29B6F6), parseWheelColorInput("41,182,246"))
        assertEquals(Color(0xFF29B6F6), parseWheelColorInput("41; 182; 246"))
        assertEquals(Color(0xFFFFFFFF), parseWheelColorInput("255 255 255"))
    }

    @Test
    fun `rejects invalid input without crashing`() {
        assertNull(parseWheelColorInput(""))
        assertNull(parseWheelColorInput("   "))
        assertNull(parseWheelColorInput("#12345"))
        assertNull(parseWheelColorInput("#GGHHII"))
        assertNull(parseWheelColorInput("256,0,0"))
        assertNull(parseWheelColorInput("-1,0,0"))
        assertNull(parseWheelColorInput("1,2"))
        assertNull(parseWheelColorInput("hello"))
    }
}
