/*
 * Copyright (C) 2026 VoltageOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.voltage.gamespace.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.window.TaskFpsCallback
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

class SessionRecorder(private val context: Context, private val gson: Gson) {

    private val json by lazy { gson.newBuilder().serializeSpecialFloatingPointValues().create() }
    private val windowManager by lazy { context.getSystemService(WindowManager::class.java) }
    private val powerManager by lazy { context.getSystemService(PowerManager::class.java) }
    private val batteryManager by lazy { context.getSystemService(BatteryManager::class.java) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val samples = ArrayList<MetricSample>(1024)
    private val tempHistory = ArrayDeque<Pair<Long, Float>>()
    private val skinZoneTemp by lazy { findSkinZone() }
    private val cpuFreqFiles by lazy {
        val policies = File("/sys/devices/system/cpu/cpufreq")
            .listFiles { f -> f.name.startsWith("policy") }
            ?.map { it.resolve("scaling_cur_freq") }
            ?.filter { readFreqMhz(it) != null }
            .orEmpty()
        policies.ifEmpty {
            File("/sys/devices/system/cpu")
                .listFiles { f -> f.name.matches(Regex("cpu\\d+")) }
                ?.map { it.resolve("cpufreq/scaling_cur_freq") }
                ?.filter { readFreqMhz(it) != null }
                .orEmpty()
        }
    }
    private val gpuFreqFile by lazy {
        val candidates = mutableListOf(
            File("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq"),
            File("/sys/class/kgsl/kgsl-3d0/gpuclk"),
            File("/sys/kernel/gpu/gpu_clock"),
            File("/sys/kernel/ged/hal/current_freqency"),
        )
        File("/sys/class/devfreq").listFiles()?.forEach { dev ->
            val name = dev.name.lowercase()
            if (GPU_DEVFREQ_KEYS.any { name.contains(it) }) {
                candidates += dev.resolve("cur_freq")
            }
        }
        candidates.firstOrNull { readFreqMhz(it) != null }
    }
    private val gpuLoadFile by lazy {
        listOf(
            "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
            "/sys/kernel/gpu/gpu_busy",
            "/sys/kernel/ged/hal/gpu_utilization",
            "/sys/class/misc/mali0/device/utilization",
            "/sys/class/kgsl/kgsl-3d0/gpubusy",
        ).map(::File).firstOrNull { readGpuLoadFrom(it) != null }
    }
    private var job: Job? = null
    private var fpsCallback: TaskFpsCallback? = null
    private var packageName = ""
    private var startedAt = 0L
    private var startLevel = -1
    private var pausedAt = 0L

    @Volatile
    private var lastFps = 0f

    val isRecording get() = job?.isActive == true

    fun start(taskId: Int, pkg: String) {
        if (isRecording) return
        val resuming = pkg == packageName &&
            pausedAt != 0L &&
            SystemClock.elapsedRealtime() - pausedAt <= RESUME_WINDOW_MS &&
            synchronized(samples) { samples.isNotEmpty() }
        if (!resuming) {
            packageName = pkg
            startedAt = System.currentTimeMillis()
            startLevel = -1
            synchronized(samples) { samples.clear() }
            tempHistory.clear()
        }
        pausedAt = 0L
        lastFps = 0f
        registerFpsCallback(taskId)
        Log.i(TAG, "recording started pkg=$pkg taskId=$taskId resumed=$resuming")
        Log.i(
            TAG,
            "telemetry cpuPolicies=${cpuFreqFiles.size}" +
                " gpuFreq=${gpuFreqFile?.path} gpuLoad=${gpuLoadFile?.path}"
        )
        job = scope.launch {
            var tick = 0
            var headroom = Float.NaN
            var status = PowerManager.THERMAL_STATUS_NONE
            while (isActive && samples.size < MAX_SAMPLES) {
                runCatching {
                    if (tick % HEADROOM_INTERVAL_TICKS == 0) {
                        headroom =
                            runCatching { powerManager.getThermalHeadroom(FORECAST_SECONDS) }
                                .getOrDefault(Float.NaN)
                        status = runCatching { powerManager.currentThermalStatus }
                            .getOrDefault(PowerManager.THERMAL_STATUS_NONE)
                    }
                    val headroomNow =
                        if (headroom.isFinite()) headroom else estimateHeadroom()
                    val batt =
                        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                    val level = batt?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                    if (startLevel < 0) startLevel = level
                    val temp = (batt?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
                        ?: Int.MIN_VALUE).let { if (it == Int.MIN_VALUE) Float.NaN else it / 10f }
                    val sample = MetricSample(
                        ts = SystemClock.elapsedRealtime(),
                        fps = lastFps,
                        headroom = headroomNow,
                        thermalStatus = status,
                        currentUa = currentNowUa(),
                        voltageMv = batt?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0,
                        level = level,
                        battTemp = temp,
                        cpuMhz = readCpuMhz(),
                        gpuMhz = readGpuMhz(),
                        gpuLoad = readGpuLoad(),
                    )
                    synchronized(samples) { samples.add(sample) }
                    if (tick > 0 && tick % FLUSH_INTERVAL_TICKS == 0) {
                        Log.i(TAG, "flushed ${writeReport(System.currentTimeMillis())} samples")
                    }
                }.onFailure { Log.w(TAG, "sampling failed", it) }
                tick++
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        unregisterFpsCallback()
        pausedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "recording stopped, ${writeReport(System.currentTimeMillis())} samples")
    }

    private fun writeReport(endedAt: Long): Int {
        val snapshot = synchronized(samples) { samples.toList() }
        if (snapshot.size < MIN_SAMPLES) return snapshot.size
        val record = SessionRecord(
            packageName = packageName,
            startedAt = startedAt,
            endedAt = endedAt,
            startLevel = startLevel,
            endLevel = snapshot.last().level,
            samples = snapshot,
        )
        runCatching { reportFile().writeText(json.toJson(record)) }
            .onFailure { Log.w(TAG, "report write failed", it) }
        return snapshot.size
    }

    fun lastRecord(): SessionRecord? = runCatching {
        val text = reportFile().takeIf { it.exists() }?.readText()
            ?: return@runCatching null
        val root = JsonParser.parseString(text).asJsonObject
        SessionRecord(
            packageName = root.get("packageName").asString,
            startedAt = root.get("startedAt").asLong,
            endedAt = root.get("endedAt").asLong,
            startLevel = root.get("startLevel").asInt,
            endLevel = root.get("endLevel").asInt,
            samples = root.getAsJsonArray("samples").map {
                val s = it.asJsonObject
                MetricSample(
                    ts = s.get("ts").asLong,
                    fps = s.get("fps").asFloat,
                    headroom = s.get("headroom").asFloat,
                    thermalStatus = s.get("thermalStatus").asInt,
                    currentUa = s.get("currentUa").asInt,
                    voltageMv = s.get("voltageMv").asInt,
                    level = s.get("level").asInt,
                    battTemp = s.get("battTemp")?.asFloat ?: Float.NaN,
                    cpuMhz = s.get("cpuMhz")?.asFloat ?: Float.NaN,
                    gpuMhz = s.get("gpuMhz")?.asFloat ?: Float.NaN,
                    gpuLoad = s.get("gpuLoad")?.asFloat ?: Float.NaN,
                )
            },
        )
    }.getOrNull()

    private fun currentNowUa(): Int {
        val raw = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Int.MIN_VALUE) return 0
        return if (abs(raw) < 10_000) raw * 1000 else raw
    }

    private fun toMhz(raw: Long): Float = when {
        raw >= 100_000_000 -> raw / 1_000_000f
        raw >= 100_000 -> raw / 1000f
        else -> raw.toFloat()
    }

    private fun readFreqMhz(file: File?): Float? {
        val text = file?.let { f -> runCatching { f.readText() }.getOrNull() } ?: return null
        val raw = Regex("\\d+").findAll(text).mapNotNull { it.value.toLongOrNull() }.maxOrNull()
            ?: return null
        return toMhz(raw).takeIf { it in 10f..10_000f }
    }

    private fun readCpuMhz(): Float =
        cpuFreqFiles.mapNotNull { readFreqMhz(it) }.maxOrNull() ?: Float.NaN

    private fun readGpuMhz(): Float = readFreqMhz(gpuFreqFile) ?: Float.NaN

    private fun readGpuLoadFrom(file: File): Float? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        val nums = Regex("\\d+(\\.\\d+)?").findAll(text)
            .mapNotNull { it.value.toFloatOrNull() }.toList()
        return when {
            nums.isEmpty() -> null
            nums.size == 2 && nums[1] > 100f && nums[1] >= nums[0] ->
                (nums[0] / nums[1] * 100f).coerceIn(0f, 100f)
            else -> nums[0].takeIf { it in 0f..100f }
        }
    }

    private fun readGpuLoad(): Float = gpuLoadFile?.let { readGpuLoadFrom(it) } ?: Float.NaN

    private fun findSkinZone(): File? {
        val zones = File("/sys/class/thermal")
            .listFiles { f -> f.name.startsWith("thermal_zone") } ?: return null
        val typed = zones.mapNotNull { zone ->
            runCatching { zone.resolve("type").readText().trim().lowercase() }
                .getOrNull()?.let { it to zone }
        }
        for (key in ZONE_PRIORITY) {
            typed.firstOrNull { it.first.contains(key) }?.let {
                Log.i(TAG, "headroom estimator using zone ${it.first}")
                return it.second.resolve("temp")
            }
        }
        Log.i(TAG, "headroom estimator found no usable thermal zone")
        return null
    }

    private fun readZoneTempC(): Float {
        val file = skinZoneTemp ?: return Float.NaN
        val raw = runCatching { file.readText().trim().toLong() }.getOrNull() ?: return Float.NaN
        return when {
            abs(raw) >= 1000 -> raw / 1000f
            abs(raw) >= 200 -> raw / 10f
            else -> raw.toFloat()
        }
    }

    private fun estimateHeadroom(): Float {
        val temp = readZoneTempC()
        if (temp.isNaN()) return Float.NaN
        val now = SystemClock.elapsedRealtime()
        tempHistory.addLast(now to temp)
        while (tempHistory.size > TEMP_HISTORY_SIZE) tempHistory.removeFirst()
        val n = tempHistory.size
        val forecast = if (n < 3) temp else {
            val t0 = tempHistory.first().first
            var sx = 0.0
            var sy = 0.0
            var sxx = 0.0
            var sxy = 0.0
            for ((t, v) in tempHistory) {
                val x = (t - t0) / 1000.0
                sx += x
                sy += v
                sxx += x * x
                sxy += x * v
            }
            val den = n * sxx - sx * sx
            if (den < 1e-6) temp else {
                val slope = (n * sxy - sx * sy) / den
                val intercept = (sy - slope * sx) / n
                val xf = (now - t0) / 1000.0 + FORECAST_SECONDS
                (intercept + slope * xf).toFloat()
            }
        }
        return ((forecast - (SKIN_LIMIT_C - HEADROOM_SPAN_C)) / HEADROOM_SPAN_C)
            .coerceAtLeast(0f)
    }

    private fun registerFpsCallback(taskId: Int) {
        if (taskId < 0) return
        val callback = object : TaskFpsCallback() {
            override fun onFpsReported(fps: Float) {
                lastFps = fps
            }
        }
        runCatching {
            windowManager.registerTaskFpsCallback(taskId, context.mainExecutor, callback)
            fpsCallback = callback
        }
    }

    private fun unregisterFpsCallback() {
        fpsCallback?.let { runCatching { windowManager.unregisterTaskFpsCallback(it) } }
        fpsCallback = null
    }

    private fun reportFile() = File(context.filesDir, REPORT_FILE)

    companion object {
        private const val TAG = "SessionRecorder"
        const val REPORT_FILE = "last_session_metrics.json"
        const val SAMPLE_INTERVAL_MS = 1000L
        const val HEADROOM_INTERVAL_TICKS = 10
        const val FORECAST_SECONDS = 15
        const val MIN_SAMPLES = 30
        const val FLUSH_INTERVAL_TICKS = 60
        const val MAX_SAMPLES = 6 * 3600
        const val RESUME_WINDOW_MS = 3 * 60_000L
        const val SKIN_LIMIT_C = 55f
        const val HEADROOM_SPAN_C = 30f
        const val TEMP_HISTORY_SIZE = 20
        private val ZONE_PRIORITY = listOf(
            "skin-therm", "skin", "ap_ntc", "mtktsap", "quiet-therm", "xo-therm",
            "sdm-therm", "msm-therm", "case-therm", "shell", "board",
        )
        private val GPU_DEVFREQ_KEYS =
            listOf("gpu", "kgsl", "mali", "g3d", "sgpu", "gpufreq", "powervr")
    }
}
