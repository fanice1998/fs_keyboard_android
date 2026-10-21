package com.fskeyboard.ime.candidate

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmailSuggestTest {
    @Test
    fun detectsEmailFields() {
        val emailSuggest = EmailSuggest()
        val emailInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val normalInfo = EditorInfo().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        }
        assertTrue(emailSuggest.isEmailField(emailInfo))
        assertFalse(emailSuggest.isEmailField(normalInfo))
    }

    @Test
    fun detectsEmailContextFromAtSymbol() {
        val emailSuggest = EmailSuggest()
        assertTrue(emailSuggest.hasEmailContext("user@", null))
        assertTrue(emailSuggest.hasEmailContext("hello user@g", null))
        assertFalse(emailSuggest.hasEmailContext("hello user", null))
    }

    @Test
    fun ranksDefaultDomainsInEmailField() {
        val emailSuggest = EmailSuggest()
        val candidates = emailSuggest.candidates("john", "john", limit = 4)
        assertEquals(
            listOf("john", "john@gmail.com", "john@yahoo.com.tw", "john@outlook.com"),
            candidates,
        )
    }

    @Test
    fun filtersDomainsAfterAtSymbol() {
        val emailSuggest = EmailSuggest()
        val candidatesG = emailSuggest.candidates("g", "john@g", limit = 3)
        assertEquals("gmail.com", candidatesG.first())

        val candidatesY = emailSuggest.candidates("y", "john@y", limit = 3)
        assertTrue(candidatesY.first().startsWith("yahoo"))
    }

    @Test
    fun weightsDomainsByUsernameHistory() {
        val emailSuggest = EmailSuggest()
        // Record that user "work" frequently uses outlook.com
        emailSuggest.recordChoice("outlook.com", "work")
        emailSuggest.recordChoice("outlook.com", "work")

        // For "work", outlook.com should outrank gmail.com
        val candidates = emailSuggest.candidates("work", "work", limit = 3)
        assertEquals("work", candidates[0])
        assertEquals("work@outlook.com", candidates[1])

        // For "other", default gmail.com should still rank first
        val otherCandidates = emailSuggest.candidates("other", "other", limit = 3)
        assertEquals("other", otherCandidates[0])
        assertEquals("other@gmail.com", otherCandidates[1])
    }
}
