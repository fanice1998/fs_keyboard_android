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

    @Test
    fun settingsPageClearsTheTitleAndPreviewsHeight() {
        val activity = readProjectFile("src/main/java/com/fskeyboard/ime/settings/ImeSettingsActivity.kt")
        val layout = readProjectFile("src/main/res/layout/activity_ime_settings.xml")
        assertTrue(activity.contains("SettingsSafeArea.padding"))
        assertTrue(activity.contains("titleBottomInWindow"))
        assertTrue(activity.contains("latin_top_number_row_switch"))
        assertTrue(layout.contains("@+id/settings_root"))
        assertTrue(layout.contains("@+id/keyboard_height_preview"))
        assertTrue(layout.contains("@+id/latin_top_number_row_switch"))
    }
}