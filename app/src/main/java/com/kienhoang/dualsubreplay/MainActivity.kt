package com.kienhoang.dualsubreplay

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kienhoang.dualsubreplay.data.KaraokeSyncPreferences
import com.kienhoang.dualsubreplay.ui.AppViewModel
import com.kienhoang.dualsubreplay.ui.LearningPlayerRoot

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KaraokeSyncPreferences.initialize(applicationContext)
        enableEdgeToEdge()
        allowContentInDisplayCutout()
        updateImmersiveMode(resources.configuration)
        setContent { LearningPlayerRoot(viewModel) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateImmersiveMode(newConfig)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateImmersiveMode(resources.configuration)
    }

    private fun allowContentInDisplayCutout() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun updateImmersiveMode(configuration: Configuration) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Keep the top status bar out of the video experience in every orientation.
        controller.hide(WindowInsetsCompat.Type.statusBars())

        // Landscape keeps the existing fully immersive behavior. Portrait keeps the navigation
        // bar available while still removing the battery/time/status row from the top.
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            controller.hide(WindowInsetsCompat.Type.navigationBars())
        } else {
            controller.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    private fun handleIntent(intent: Intent?) {
        val sharedText = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        }
        if (!sharedText.isNullOrBlank()) viewModel.acceptSharedText(sharedText)
    }
}
