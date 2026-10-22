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
        val strings = readProjectFile("src/main/res/values/strings.xml")
        assertTrue(activity.contains("SettingsSafeArea.padding"))
        assertTrue(activity.contains("titleBottomInWindow"))
        assertTrue(activity.contains("latin_top_number_row_switch"))
        assertTrue(activity.contains("vibration_switch"))
        assertTrue(layout.contains("@+id/settings_root"))
        assertTrue(layout.contains("@+id/keyboard_height_preview"))
        assertTrue(layout.contains("@+id/latin_top_number_row_switch"))
        assertTrue(layout.contains("@+id/vibration_switch"))
        assertTrue(strings.contains("name=\"vibration_switch\""))
    }

    @Test
    fun settingsPageOffersAKeyPreviewSwitch() {
        val layout = readProjectFile("src/main/res/layout/activity_ime_settings.xml")
        val strings = readProjectFile("src/main/res/values/strings.xml")
        val activity = readProjectFile("src/main/java/com/fskeyboard/ime/settings/ImeSettingsActivity.kt")
        val service = readProjectFile("src/main/java/com/fskeyboard/ime/FsKeyboardService.kt")
        val preferences = readProjectFile("src/main/java/com/fskeyboard/ime/settings/KeyboardPreferences.kt")
        assertTrue(layout.contains("@+id/key_preview_switch"))
        assertTrue(strings.contains("name=\"key_preview_switch\""))
        assertTrue(activity.contains("key_preview_switch"))
        assertTrue(activity.contains("setKeyPreviewEnabled"))
        assertTrue(service.contains("keyPreviewEnabled"))
        assertTrue(preferences.contains("key_preview_enabled"))
        assertTrue(preferences.contains("getBoolean(KEY_PREVIEW, true)"))
    }
}
