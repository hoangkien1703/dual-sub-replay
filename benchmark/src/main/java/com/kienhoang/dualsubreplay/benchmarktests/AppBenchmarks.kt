package com.kienhoang.dualsubreplay.benchmarktests

import android.content.Intent
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test

private const val PACKAGE = "com.kienhoang.dualsubreplay.benchmark"

private fun fixture(journey: String) =
    Intent()
        .setClassName(
            PACKAGE,
            "com.kienhoang.dualsubreplay.ui.BenchmarkActivity",
        ).putExtra("journey", journey)

class AppBenchmarks {
    @get:Rule val benchmark = MacrobenchmarkRule()

    @Test fun startup() =
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            iterations = 5,
            startupMode = StartupMode.COLD,
            compilationMode = CompilationMode.Partial(),
            setupBlock = { pressHome() },
        ) { startActivityAndWait() }

    @Test fun practiceScroll() = scroll("practice")

    @Test fun subtitleScroll() = scroll("subtitles")

    private fun scroll(journey: String) =
        benchmark.measureRepeated(
            packageName = PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            iterations = 5,
            compilationMode = CompilationMode.Partial(),
            setupBlock = {
                startActivityAndWait(fixture(journey))
                check(device.wait(Until.hasObject(By.scrollable(true)), 10_000))
            },
        ) {
            val list = device.findObject(By.scrollable(true))
            list.setGestureMargin(device.displayWidth / 5)
            repeat(3) { list.scroll(Direction.DOWN, 0.8f) }
            device.waitForIdle()
        }
}

class AppBaselineProfile {
    @get:Rule val profile = BaselineProfileRule()

    @Test fun generate() =
        profile.collect(
            packageName = PACKAGE,
            filterPredicate = { it.contains("Lcom/kienhoang/dualsubreplay/") },
        ) {
            pressHome()
            startActivityAndWait()
            listOf("subtitles", "practice").forEach { journey ->
                startActivityAndWait(fixture(journey))
                check(device.wait(Until.hasObject(By.scrollable(true)), 10_000))
                val list = device.findObject(By.scrollable(true))
                list.setGestureMargin(device.displayWidth / 5)
                list.scroll(Direction.DOWN, 0.8f)
                device.waitForIdle()
            }
        }
}
