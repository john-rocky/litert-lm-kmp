package io.github.johnrocky.litertlm.sample

import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app's theme is always light, so the bars keep dark icons on a phone in dark mode too.
        val bars = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        val extras = intent.extras?.let { bundle ->
            bundle.keySet().mapNotNull { key -> bundle.getString(key)?.let { key to it } }.toMap()
        }.orEmpty()
        // A download needs the screen on: Android can stop the network of an app that is not on screen.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (extras.isNotEmpty()) {
            // Launched for a check, with nobody at the phone: Gemini Nano answers only the app on top.
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        startDeviceCheckIfRequested(this, extras)
        // App-specific external storage: a download lands here, and `adb push` can put a model here.
        val modelsDir = (getExternalFilesDir(null) ?: filesDir).absolutePath
        val launch = SampleLaunch.from(extras, baseDir = modelsDir)
        setContent { SampleRoot(modelsDir = modelsDir, cacheDir = cacheDir.absolutePath, launch = launch) }
    }
}
