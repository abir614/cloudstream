package com.lagradost.cloudstream3.di

import com.lagradost.cloudstream3.domain.engine.DownloadQueueEngine
import com.lagradost.cloudstream3.domain.engine.PluginArchiveIntegrityEngine
import com.lagradost.cloudstream3.domain.engine.StreamFailoverEngine
import com.lagradost.cloudstream3.domain.engine.SubtitleSyncEngine
import com.lagradost.cloudstream3.domain.usecase.AppUpdateUseCase
import com.lagradost.cloudstream3.domain.usecase.ResumePlaybackUseCase
import com.lagradost.cloudstream3.domain.usecase.StreamResolutionUseCase
import com.lagradost.cloudstream3.domain.usecase.StreamSecurityUseCase
import com.lagradost.cloudstream3.domain.usecase.TrackSelectionUseCase
import com.lagradost.cloudstream3.services.ExtensionSandboxClient
import com.lagradost.cloudstream3.ui.coordinators.AppUpdateCoordinator
import com.lagradost.cloudstream3.ui.coordinators.DeepLinkCoordinator
import com.lagradost.cloudstream3.ui.coordinators.TvLayoutCoordinator
import com.lagradost.cloudstream3.utils.persistence.DataRepository
import com.lagradost.cloudstream3.utils.persistence.DefaultDataRepository
import com.lagradost.cloudstream3.utils.telemetry.TelemetryService
import java.util.concurrent.ConcurrentHashMap

/**
 * Enterprise Service Locator & Dependency Injection Container.
 * Provides inversion of control, lifecycle management, and test-swappable mocks.
 */
object ServiceLocator {

    @PublishedApi internal val dependencies = ConcurrentHashMap<Class<*>, Any>()
    @PublishedApi internal val factories = ConcurrentHashMap<Class<*>, () -> Any>()

    init {
        registerDefaults()
    }

    private fun registerDefaults() {
        val repo = DefaultDataRepository()
        register<DataRepository>(repo)
        val security = StreamSecurityUseCase()
        register(security)
        val updateUseCase = AppUpdateUseCase()
        register(updateUseCase)
        register(TrackSelectionUseCase())
        register(ResumePlaybackUseCase(repo))
        register(StreamResolutionUseCase(security))
        register(TelemetryService())
        register(StreamFailoverEngine())
        register(SubtitleSyncEngine())
        register(DownloadQueueEngine())
        register(PluginArchiveIntegrityEngine())
        register(DeepLinkCoordinator())
        register(TvLayoutCoordinator())
        register(AppUpdateCoordinator(updateUseCase))
    }

    /**
     * Registers a singleton dependency instance.
     */
    inline fun <reified T : Any> register(instance: T) {
        register(T::class.java, instance)
    }

    fun <T : Any> register(clazz: Class<T>, instance: T) {
        dependencies[clazz] = instance
    }

    /**
     * Registers a factory for creating transient or on-demand instances.
     */
    inline fun <reified T : Any> registerFactory(noinline factory: () -> T) {
        registerFactory(T::class.java, factory)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> registerFactory(clazz: Class<T>, factory: () -> T) {
        factories[clazz] = factory as () -> Any
    }

    /**
     * Resolves a dependency. Throws IllegalStateException if not registered.
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> resolve(): T {
        return resolve(T::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolve(clazz: Class<T>): T {
        dependencies[clazz]?.let { return it as T }
        factories[clazz]?.let { return it() as T }
        throw IllegalStateException("Dependency not registered in ServiceLocator: ${clazz.name}")
    }

    /**
     * Resolves a dependency or returns null if not registered.
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <reified T : Any> resolveOrNull(): T? {
        return resolveOrNull(T::class.java)
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> resolveOrNull(clazz: Class<T>): T? {
        dependencies[clazz]?.let { return it as T }
        factories[clazz]?.let { return it() as T }
        return null
    }

    /**
     * Resets dependencies (useful in unit testing).
     */
    fun resetForTesting() {
        dependencies.clear()
        factories.clear()
        registerDefaults()
    }

    // Convenience property accessors for core architectural services
    val dataRepository: DataRepository get() = resolve()
    val sandboxClient: ExtensionSandboxClient get() = ExtensionSandboxClient
    val streamSecurityUseCase: StreamSecurityUseCase get() = resolve()
    val appUpdateUseCase: AppUpdateUseCase get() = resolve()
    val trackSelectionUseCase: TrackSelectionUseCase get() = resolve()
    val resumePlaybackUseCase: ResumePlaybackUseCase get() = resolve()
    val streamResolutionUseCase: StreamResolutionUseCase get() = resolve()
    val telemetry: TelemetryService get() = resolve()
    val streamFailoverEngine: StreamFailoverEngine get() = resolve()
    val subtitleSyncEngine: SubtitleSyncEngine get() = resolve()
    val downloadQueueEngine: DownloadQueueEngine get() = resolve()
    val pluginArchiveIntegrityEngine: PluginArchiveIntegrityEngine get() = resolve()
    val deepLinkCoordinator: DeepLinkCoordinator get() = resolve()
    val tvLayoutCoordinator: TvLayoutCoordinator get() = resolve()
    val appUpdateCoordinator: AppUpdateCoordinator get() = resolve()
}
