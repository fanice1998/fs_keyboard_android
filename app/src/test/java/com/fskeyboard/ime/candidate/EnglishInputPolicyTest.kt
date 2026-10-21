package com.fskeyboard.ime.candidate

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnglishInputPolicyTest {

    private fun editorInfo(inputType: Int): EditorInfo {
        return EditorInfo().apply { this.inputType = inputType }
    }

    @Test
    fun nullInfoDisablesComposing() {
        assertFalse(EnglishInputPolicy.shouldCompose(null))
        assertFalse(EnglishInputPolicy.isPassword(null))
    }

    @Test
    fun standardTextFieldEnablesComposing() {
        val normalText = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL)
        assertTrue(EnglishInputPolicy.shouldCompose(normalText))
        assertFalse(EnglishInputPolicy.isPassword(normalText))

        val longMessage = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_LONG_MESSAGE)
        assertTrue(EnglishInputPolicy.shouldCompose(longMessage))
    }

    @Test
    fun passwordFieldsDisableComposing() {
        val textPassword = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        assertFalse(EnglishInputPolicy.shouldCompose(textPassword))
        assertTrue(EnglishInputPolicy.isPassword(textPassword))

        val visiblePassword = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
        assertFalse(EnglishInputPolicy.shouldCompose(visiblePassword))
        assertTrue(EnglishInputPolicy.isPassword(visiblePassword))

        val webPassword = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
        assertFalse(EnglishInputPolicy.shouldCompose(webPassword))
        assertTrue(EnglishInputPolicy.isPassword(webPassword))

        val numberPassword = editorInfo(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD)
        assertFalse(EnglishInputPolicy.shouldCompose(numberPassword))
        assertTrue(EnglishInputPolicy.isPassword(numberPassword))
    }

    @Test
    fun noSuggestionsFlagDisablesComposing() {
        val noSuggestions = editorInfo(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
        )
        assertFalse(EnglishInputPolicy.shouldCompose(noSuggestions))
    }

    @Test
    fun uriAndFilterFieldsDisableComposing() {
        val uriField = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        assertFalse(EnglishInputPolicy.shouldCompose(uriField))

        val filterField = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER)
        assertFalse(EnglishInputPolicy.shouldCompose(filterField))
    }

    @Test
    fun nonTextClassesDisableComposing() {
        val numberField = editorInfo(InputType.TYPE_CLASS_NUMBER)
        assertFalse(EnglishInputPolicy.shouldCompose(numberField))

        val phoneField = editorInfo(InputType.TYPE_CLASS_PHONE)
        assertFalse(EnglishInputPolicy.shouldCompose(phoneField))

        val dateField = editorInfo(InputType.TYPE_CLASS_DATETIME)
        assertFalse(EnglishInputPolicy.shouldCompose(dateField))

        val nullField = editorInfo(InputType.TYPE_NULL)
        assertFalse(EnglishInputPolicy.shouldCompose(nullField))
    }

    @Test
    fun emailFieldEnablesComposingForSuggestions() {
        val email = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        assertTrue(EnglishInputPolicy.shouldCompose(email))

        val webEmail = editorInfo(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        assertTrue(EnglishInputPolicy.shouldCompose(webEmail))
    }
}
