package com.omnidev.workspace.data.voice

import org.junit.Assert.*
import org.junit.Test

class SpokenCredentialTest {
    @Test fun digitsHaveNoNumberGuessingAndSupportArabicForms() {
        SpokenCredentialParser.parse("one two zero nine", SpokenCredential.Kind.PIN).use { assertArrayEquals(charArrayOf('1', '2', '0', '9'), it.chars) }
        SpokenCredentialParser.parse("واحد اتنين ثلاثة صفر", SpokenCredential.Kind.PIN).use { assertArrayEquals(charArrayOf('1', '2', '3', '0'), it.chars) }
        SpokenCredentialParser.parse("۱۲٣٤", SpokenCredential.Kind.PIN).use { assertArrayEquals(charArrayOf('1', '2', '3', '4'), it.chars) }
        assertThrows(IllegalArgumentException::class.java) { SpokenCredentialParser.parse("twelve thirty four", SpokenCredential.Kind.PIN) }
        assertThrows(IllegalArgumentException::class.java) { SpokenCredentialParser.parse("one two three", SpokenCredential.Kind.PIN) }
    }
    @Test fun passwordsUseExplicitCaseAndSymbolSpelling() {
        SpokenCredentialParser.parse("capital alpha bravo at five hash underscore", SpokenCredential.Kind.PASSWORD).use {
            assertArrayEquals(charArrayOf('A', 'b', '@', '5', '#', '_'), it.chars)
            assertEquals("SpokenCredential(REDACTED)", it.toString())
        }
        SpokenCredentialParser.parse("arabic الف arabic باء one star", SpokenCredential.Kind.PASSWORD).use { assertArrayEquals(charArrayOf('ا', 'ب', '1', '*'), it.chars) }
        assertThrows(IllegalStateException::class.java) { SpokenCredentialParser.parse("secret guessing word", SpokenCredential.Kind.PASSWORD) }
    }
    @Test fun credentialArraysAreZeroedOnClose() {
        val value = SpokenCredentialParser.parse("one two three four", SpokenCredential.Kind.PIN)
        val borrowed = value.chars
        value.close()
        assertTrue(borrowed.all { it == '\u0000' })
    }
    @Test fun patternsIncludeAndroidSkippedMidpointsAndRejectDuplicates() {
        SpokenCredentialParser.parse("one three nine seven", SpokenCredential.Kind.PATTERN).use { assertArrayEquals(charArrayOf('1', '2', '3', '6', '9', '8', '7'), it.chars) }
        assertThrows(IllegalArgumentException::class.java) { SpokenCredentialParser.parse("one three two five", SpokenCredential.Kind.PATTERN) }
        assertThrows(IllegalArgumentException::class.java) { SpokenCredentialParser.parse("one two zero four", SpokenCredential.Kind.PATTERN) }
        assertThrows(IllegalArgumentException::class.java) { SpokenCredentialParser.parse("one nine", SpokenCredential.Kind.PATTERN) }
    }
    @Test fun patternInputIsZeroedIncludingFailures() {
        val input = charArrayOf('1', '2', '5', '8')
        assertArrayEquals(charArrayOf('1', '2', '5', '8'), PatternSequence.expand(input))
        assertTrue(input.all { it == '\u0000' })
        val invalid = charArrayOf('1', '0')
        assertThrows(IllegalArgumentException::class.java) { PatternSequence.expand(invalid) }
        assertTrue(invalid.all { it == '\u0000' })
    }
}
