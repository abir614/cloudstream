package com.lagradost.cloudstream3.ui.coordinators

enum class LayoutMode {
    TV,
    PHONE_TABLET,
    EMULATOR
}

/**
 * Decoupled coordinator for managing form-factor layout negotiation,
 * TV 10-foot UI configuration, and D-Pad focus outlines.
 */
class TvLayoutCoordinator {

    /**
     * Resolves layout mode according to user preference flags and system features.
     */
    fun resolveLayoutMode(
        userPreferenceLayout: Int,
        isSystemTelevision: Boolean,
        hasLeanbackFeature: Boolean
    ): LayoutMode {
        // Flags: 1 = TV, 2 = EMULATOR
        val isTvPref = (userPreferenceLayout and 1) != 0
        val isEmulatorPref = (userPreferenceLayout and 2) != 0

        return when {
            isTvPref || isSystemTelevision || hasLeanbackFeature -> LayoutMode.TV
            isEmulatorPref -> LayoutMode.EMULATOR
            else -> LayoutMode.PHONE_TABLET
        }
    }

    /**
     * Determines whether focus outlines should be rendered for D-Pad navigation.
     */
    fun shouldRenderFocusOutline(mode: LayoutMode, userAnimatedOutlineEnabled: Boolean): Boolean {
        return mode == LayoutMode.TV && userAnimatedOutlineEnabled
    }

    /**
     * Determines if a view id is an exception to centering behavior during D-Pad navigation.
     */
    fun isFocusCenteringException(viewId: Int, exceptionIds: Set<Int>): Boolean {
        return exceptionIds.contains(viewId)
    }
}
