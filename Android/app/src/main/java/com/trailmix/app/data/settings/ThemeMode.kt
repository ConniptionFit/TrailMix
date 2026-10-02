package com.trailmix.app.data.settings

/**
 * G2 (UX overhaul): the three-way theme choice. Replaces the old boolean "dark mode override"
 * where null meant "follow the system" but nothing in the UI could get you back to it.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    ;

    /** `null` = follow the system; otherwise the forced value. */
    val darkOverride: Boolean?
        get() = when (this) {
            SYSTEM -> null
            LIGHT -> false
            DARK -> true
        }

    companion object {
        /**
         * Resolves what is stored. The new `theme_mode` string wins; otherwise the legacy
         * `dark_mode_override` boolean is migrated (true -> DARK, false -> LIGHT), and absent
         * -> SYSTEM. Unknown strings degrade to SYSTEM rather than throwing.
         */
        fun resolve(stored: String?, legacyDarkOverride: Boolean?): ThemeMode {
            if (stored != null) return entries.firstOrNull { it.name == stored } ?: SYSTEM
            return when (legacyDarkOverride) {
                true -> DARK
                false -> LIGHT
                null -> SYSTEM
            }
        }
    }
}
