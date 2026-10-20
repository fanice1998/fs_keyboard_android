package com.fskeyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeSettingsContractTest {
    @Test
    fun systemKeyboardListOpensTheSettingsActivity() {
        val method = readProjectFile("src/main/res/xml/method.xml")
        val manifest = readProjectFile("src/main/AndroidManifest.xml")
        val inputMethod = method.substringBefore("<subtype")
        assertTrue(inputMethod.contains(
            "android:settingsActivity=\"com.fskeyboard.ime.settings.ImeSettingsActivity\"",
        ))
        assertFalse(manifest.contains("android:settingsActivity"))
        val activity = manifest.substringAfter("android:name=\".settings.ImeSettingsActivity\"")
            .substringBefore("</activity>")
        assertTrue(activity.contains("android:exported=\"true\""))
        assertTrue(activity.contains("android.intent.action.MAIN"))
    }
}