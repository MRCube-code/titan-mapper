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
