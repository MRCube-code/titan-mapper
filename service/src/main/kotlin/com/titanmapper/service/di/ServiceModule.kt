package com.titanmapper.service.di

import com.titanmapper.service.api.ActivityInputBridge
import com.titanmapper.service.api.CapabilityMonitor
import com.titanmapper.service.api.InputEventStream
import com.titanmapper.service.api.LatencyMonitor
import com.titanmapper.service.api.MacroRecorder
import com.titanmapper.service.api.MacroRunner
import com.titanmapper.service.api.PerformanceMonitor
import com.titanmapper.service.api.PluginRegistry
import com.titanmapper.service.api.SelfTestRunner
import com.titanmapper.service.api.ServiceController
import com.titanmapper.service.input.ActivityInputBridgeImpl
import com.titanmapper.service.input.InputEventBus
import com.titanmapper.service.macro.MacroExecutorImpl
import com.titanmapper.service.macro.MacroRecorderImpl
import com.titanmapper.service.permission.CapabilityProbe
import com.titanmapper.service.pipeline.LatencyMonitorImpl
import com.titanmapper.service.pipeline.PerformanceMonitorImpl
import com.titanmapper.service.pipeline.PipelineCoordinator
import com.titanmapper.service.plugin.PluginRegistryImpl
import com.titanmapper.service.selftest.SelfTestRunnerImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the :service public contract (ServiceApi.kt) to its implementations. Feature and app
 * modules inject only the left-hand-side interfaces here, never the impl classes directly,
 * which is what keeps the capture/dispatch internals free to change (Architecture §3).
 *
 * [LatencyMonitorImpl] and [PerformanceMonitorImpl] are also injected elsewhere (e.g. by
 * [PipelineCoordinator]) as their CONCRETE type to reach `setEnabled`/`start`, which aren't part
 * of the public interface — Hilt's implicit `@Inject constructor` binding already provides the
 * concrete type for free, so only the interface mapping needs declaring here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ServiceBindingsModule {
    @Binds @Singleton abstract fun serviceController(impl: PipelineCoordinator): ServiceController
    @Binds @Singleton abstract fun capabilityMonitor(impl: CapabilityProbe): CapabilityMonitor
    @Binds @Singleton abstract fun inputEventStream(impl: InputEventBus): InputEventStream
    @Binds @Singleton abstract fun activityInputBridge(impl: ActivityInputBridgeImpl): ActivityInputBridge
    @Binds @Singleton abstract fun macroRunner(impl: MacroExecutorImpl): MacroRunner
    @Binds @Singleton abstract fun macroRecorder(impl: MacroRecorderImpl): MacroRecorder
    @Binds @Singleton abstract fun pluginRegistry(impl: PluginRegistryImpl): PluginRegistry
    @Binds @Singleton abstract fun latencyMonitor(impl: LatencyMonitorImpl): LatencyMonitor
    @Binds @Singleton abstract fun performanceMonitor(impl: PerformanceMonitorImpl): PerformanceMonitor
    @Binds @Singleton abstract fun selfTestRunner(impl: SelfTestRunnerImpl): SelfTestRunner
}
