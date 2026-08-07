package com.oneasmr.app

import android.app.Application
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application entry point wired into the dependency graph by Hilt.
 * Registered in AndroidManifest.xml via `android:name=".OneAsmrApp"`.
 *
 * Registers the Hilt-provided Coil [ImageLoader] (CoverModule) as Coil's
 * process singleton so every AsyncImage resolves to ONE loader — the
 * "one ImageLoader wired via Hilt" rule of plan Task 10.
 */
@HiltAndroidApp
class OneAsmrApp : Application() {

    @Inject
    lateinit var imageLoader: ImageLoader

    override fun onCreate() {
        super.onCreate()
        SingletonImageLoader.setSafe { imageLoader }
    }
}
