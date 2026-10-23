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

    @Test
    fun settingsPageOffersTheZhuyinComputerLayout() {
        val layout = readProjectFile("src/main/res/layout/activity_ime_settings.xml")
        val strings = readProjectFile("src/main/res/values/strings.xml")
        val activity = readProjectFile("src/main/java/com/fskeyboard/ime/settings/ImeSettingsActivity.kt")
        val service = readProjectFile("src/main/java/com/fskeyboard/ime/FsKeyboardService.kt")
        val preferences = readProjectFile("src/main/java/com/fskeyboard/ime/settings/KeyboardPreferences.kt")
        assertTrue(layout.contains("@+id/zhuyin_computer_layout_switch"))
        assertTrue(strings.contains("name=\"zhuyin_computer_layout_switch\""))
        assertTrue(activity.contains("setZhuyinComputerLayout"))
        assertTrue(service.contains("zhuyinComputerLayout"))
        assertTrue(preferences.contains("zhuyin_computer_layout"))
        assertTrue(preferences.contains("getBoolean(KEY_ZHUYIN_COMPUTER_LAYOUT, false)"))
    }

    @Test
    fun settingsPageOffersComputerKeysOnTheEnglishKeyboard() {
        val layout = readProjectFile("src/main/res/layout/activity_ime_settings.xml")
        val strings = readProjectFile("src/main/res/values/strings.xml")
        val activity = readProjectFile("src/main/java/com/fskeyboard/ime/settings/ImeSettingsActivity.kt")
        val service = readProjectFile("src/main/java/com/fskeyboard/ime/FsKeyboardService.kt")
        val preferences = readProjectFile("src/main/java/com/fskeyboard/ime/settings/KeyboardPreferences.kt")
        assertTrue(layout.contains("@+id/latin_computer_keys_switch"))
        assertTrue(strings.contains("name=\"latin_computer_keys_switch\""))
        assertTrue(activity.contains("setComputerKeys"))
        assertTrue(service.contains("computerKeys"))
        assertTrue(preferences.contains("latin_computer_keys"))
        assertTrue(preferences.contains("getBoolean(KEY_COMPUTER_KEYS, false)"))
        assertTrue(layout.contains("@+id/computer_key_editor"))
        assertTrue(activity.contains("setComputerKeyMap"))
        assertTrue(service.contains("computerMap"))
        assertTrue(preferences.contains("latin_computer_key_map"))
        assertTrue(preferences.contains("prefs.contains(KEY_COMPUTER_KEY_MAP)"))
    }
}
