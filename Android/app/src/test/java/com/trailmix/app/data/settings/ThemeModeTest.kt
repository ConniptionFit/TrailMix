package com.trailmix.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThemeModeTest {
    @Test fun absentEverythingFollowsSystem() = assertEquals(ThemeMode.SYSTEM, ThemeMode.resolve(null, null))

    @Test fun legacyTrueMigratesToDark() = assertEquals(ThemeMode.DARK, ThemeMode.resolve(null, true))

    @Test fun legacyFalseMigratesToLight() = assertEquals(ThemeMode.LIGHT, ThemeMode.resolve(null, false))

    @Test fun newKeyWinsOverLegacy() = assertEquals(ThemeMode.SYSTEM, ThemeMode.resolve("SYSTEM", true))

    @Test fun unknownStoredValueDegradesToSystem() = assertEquals(ThemeMode.SYSTEM, ThemeMode.resolve("SEPIA", false))

    @Test fun overrideMapping() {
        assertNull(ThemeMode.SYSTEM.darkOverride)
        assertEquals(false, ThemeMode.LIGHT.darkOverride)
        assertEquals(true, ThemeMode.DARK.darkOverride)
    }
}
