package com.titanmapper.service.pipeline

import com.titanmapper.service.services.TitanAccessibilityService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Current foreground package, used by automatic profile switching. Only the Accessibility tier
 * can observe this without extra permissions (via TYPE_WINDOW_STATE_CHANGED events); on other
 * tiers it stays null and profiles fall back to their global/device-only matching rules.
 */
@Singleton
class ForegroundAppTracker @Inject constructor() {
    val foregroundPackage: StateFlow<String?> get() = TitanAccessibilityService.foregroundPackage
}
