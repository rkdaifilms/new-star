package com.newstar.live

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NativePreferencesSyncTest {
    @Test
    fun acceptsSupportedLanguageAndThemeOnly() {
        assertEquals("ne", NativePreferencesSync.validString(JSONObject().put("language", "ne"), "language", NativePreferencesSync.languages))
        assertNull(NativePreferencesSync.validString(JSONObject().put("language", "xx"), "language", NativePreferencesSync.languages))
        assertEquals("aurora", NativePreferencesSync.validString(JSONObject().put("theme", "aurora"), "theme", NativePreferencesSync.themes))
        assertNull(NativePreferencesSync.validString(JSONObject().put("theme", "system-shell"), "theme", NativePreferencesSync.themes))
    }

    @Test
    fun normalizesNumericTextScaleAndRejectsUnsupportedValues() {
        assertEquals("1", NativePreferencesSync.validTextScale(JSONObject().put("textScale", 1.0)))
        assertEquals("1.25", NativePreferencesSync.validTextScale(JSONObject().put("textScale", 1.25)))
        assertNull(NativePreferencesSync.validTextScale(JSONObject().put("textScale", 2.0)))
        assertNull(NativePreferencesSync.validTextScale(JSONObject()))
    }

    @Test
    fun readsBooleanPreferenceWithoutTreatingInvalidTextAsFalse() {
        assertTrue(NativePreferencesSync.validBoolean(JSONObject().put("enabled", true), "enabled") == true)
        assertFalse(NativePreferencesSync.validBoolean(JSONObject().put("enabled", "false"), "enabled")!!)
        assertNull(NativePreferencesSync.validBoolean(JSONObject().put("enabled", "sometimes"), "enabled"))
        assertNull(NativePreferencesSync.validBoolean(JSONObject(), "enabled"))
    }
}
