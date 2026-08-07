package com.oneasmr.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Application entry point wired into the dependency graph by Hilt.
 * Registered in AndroidManifest.xml via `android:name=".OneAsmrApp"`.
 */
@HiltAndroidApp
class OneAsmrApp : Application()
