package com.oneasmr.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.oneasmr.app.navigation.OneAsmrNavHost
import com.oneasmr.app.ui.common.OneAsmrTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * Single-activity host for the whole app. All navigation flows through
 * [OneAsmrNavHost]; screens are scaffolded in Task 2 and filled in later waves.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            OneAsmrTheme {
                OneAsmrNavHost()
            }
        }
    }
}
