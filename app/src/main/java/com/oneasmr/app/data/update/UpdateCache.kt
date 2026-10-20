package com.oneasmr.app.data.update

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide holder of the app-startup silent check result, so the Settings
 * screen can surface a pending update without waiting for a fresh network
 * round-trip (the startup check runs once per process launch).
 */
@Singleton
class UpdateCache @Inject constructor() {

    private val _result = MutableStateFlow<UpdateCheckResult?>(null)
    val result: StateFlow<UpdateCheckResult?> = _result.asStateFlow()

    fun publish(result: UpdateCheckResult) {
        _result.value = result
    }
}
