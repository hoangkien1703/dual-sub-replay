package com.kienhoang.dualsubreplay.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

internal fun saveUiEvidence(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
    val directory = File(output).apply { mkdirs() }
    val screenshot = instrumentation.uiAutomation.takeScreenshot() ?: return
    File(directory, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    screenshot.recycle()
}

internal fun saveUiEvidence(
    name: String,
    image: ImageBitmap,
) {
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir") ?: return
    val directory = File(output).apply { mkdirs() }
    File(directory, "$name.png").outputStream().use {
        image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    }
}
