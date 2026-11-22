package com.titanmapper.core.common

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Long-lived scope for work that must outlive a screen (service pipelines, log writers). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/**
 * The actual installed app's versionCode (read from PackageManager at injection time), for
 * anything that has to compare against it -- e.g. the update checker. Deliberately NOT read from
 * :app's BuildConfig: :feature:* modules can't depend on :app (that dependency runs the other
 * way), so a feature module has no honest way to reach the real BuildConfig.VERSION_CODE, and a
 * local stand-in constant left in a feature module is exactly the kind of "looks wired up but
 * isn't" bug this qualifier exists to prevent.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class InstalledVersionCode
