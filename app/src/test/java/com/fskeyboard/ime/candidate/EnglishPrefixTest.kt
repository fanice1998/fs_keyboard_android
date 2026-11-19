package com.fskeyboard.ime.candidate

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishPrefixTest {
    @Test
    fun aWholeWordIsKept() {
        assertEquals("hello", englishPrefixBefore("hello"))
    }

    @Test
    fun aWordAfterASpaceIsTaken() {
        assertEquals("hello", englishPrefixBefore("say hello"))
    }

    @Test
    fun aWordAfterANewlineIsTaken() {
        assertEquals("world", englishPrefixBefore("hello\nworld"))
    }

    @Test
    fun aHyphenatedWordIsKeptWhole() {
        assertEquals("well-known", englishPrefixBefore("well-known"))
    }

    @Test
    fun anApostropheInsideAWordIsKept() {
        assertEquals("it's", englishPrefixBefore("it's"))
    }

    @Test
    fun digitsAfterALetterAreKept() {
        assertEquals("a123", englishPrefixBefore("a123"))
    }

    @Test
    fun aLeadingApostropheIsDropped() {
        assertEquals("hello", englishPrefixBefore("'hello"))
    }

    @Test
    fun aLeadingHyphenIsDropped() {
        assertEquals("a", englishPrefixBefore("-a"))
    }

    @Test
    fun digitsAloneGiveAnEmptyPrefix() {
        assertEquals("", englishPrefixBefore("123"))
    }

    @Test
    fun leadingDigitsAreDroppedUntilALetter() {
        assertEquals("abc", englishPrefixBefore("123abc"))
    }

    @Test
    fun cjkCharactersCutThePrefix() {
        assertEquals("hello", englishPrefixBefore("你好hello"))
    }

    @Test
    fun aMidWordCaretTakesWhatWasPassedIn() {
        assertEquals("hel", englishPrefixBefore("hel"))
    }

    @Test
    fun anAtSignCutsThePrefix() {
        assertEquals("gm", englishPrefixBefore("user@gm"))
    }

    @Test
    fun capitalizationIsPreserved() {
        assertEquals("Hello", englishPrefixBefore("Hello"))
    }

    @Test
    fun onlyTheWordAfterTheLastSpaceIsTaken() {
        assertEquals("HELLO", englishPrefixBefore("SAY HELLO"))
    }

    @Test
    fun mixedCaseIsNotRewritten() {
        assertEquals("HeLLo", englishPrefixBefore("HeLLo"))
    }

    @Test
    fun longWordsAreNotTruncated() {
        assertEquals("internationalization", englishPrefixBefore("internationalization"))
    }

    @Test
    fun emptyInputGivesAnEmptyPrefix() {
        assertEquals("", englishPrefixBefore(""))
    }

    @Test
    fun aTrailingSpaceMeansNoPrefix() {
        assertEquals("", englishPrefixBefore("hello "))
    }

    @Test
    fun aWordAfterATabIsTaken() {
        assertEquals("world", englishPrefixBefore("hello\tworld"))
    }

    @Test
    fun trailingDigitsAfterLettersAreKept() {
        assertEquals("abc456", englishPrefixBefore("123abc456"))
    }

    @Test
    fun aLeadingApostropheBeforeAContractionIsDropped() {
        assertEquals("it's", englishPrefixBefore("'it's"))
    }

    @Test
    fun aLeadingHyphenBeforeAHyphenatedWordIsDropped() {
        assertEquals("well-known", englishPrefixBefore("-well-known"))
    }

    @Test
    fun aTrailingHyphenAfterALetterIsKept() {
        assertEquals("a-", englishPrefixBefore("a-"))
    }

    @Test
    fun aTrailingApostropheAfterALetterIsKept() {
        assertEquals("a'", englishPrefixBefore("a'"))
    }
}
