package com.oneasmr.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.oneasmr.app.data.update.UpdateCache
import com.oneasmr.app.data.update.UpdateRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application entry point wired into the dependency graph by Hilt.
 * Registered in AndroidManifest.xml via `android:name=".OneAsmrApp"`.
 *
 * Registers the Hilt-provided Coil [ImageLoader] (CoverModule) as Coil's
 * process singleton so every AsyncImage resolves to ONE loader — the
 * "one ImageLoader wired via Hilt" rule of plan Task 10.
 *
 * Also kicks off the silent app-startup update check (自更新): the result is
 * published into [UpdateCache] so the Settings screen can surface a pending
 * version without an extra network round-trip. One lightweight GitHub API
 * call per process launch (the repository itself never throws — failures
 * surface as [com.oneasmr.app.data.update.UpdateCheckResult.Error]).
 */
@HiltAndroidApp
class OneAsmrApp : Application() {

    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var updateRepository: UpdateRepository

    @Inject
    lateinit var updateCache: UpdateCache

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { imageLoader }
        appScope.launch {
            updateCache.publish(updateRepository.checkForUpdate())
        }
    }
}
