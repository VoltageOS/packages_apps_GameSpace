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
package com.voltage.gamespace.settings

import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import dagger.hilt.android.AndroidEntryPoint
import com.voltage.gamespace.R
import com.voltage.gamespace.data.SessionRecorder
import com.voltage.gamespace.widget.SessionGraphView
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint(CollapsingToolbarBaseActivity::class)
class SessionReportActivity : Hilt_SessionReportActivity() {

    @Inject
    lateinit var recorder: SessionRecorder

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        layoutInflater.inflate(
            R.layout.activity_session_report,
            findViewById(com.android.settingslib.collapsingtoolbar.R.id.content_frame),
            true
        )
        setTitle(R.string.session_report_title)
        bind()
    }

    private fun bind() {
        val empty = findViewById<TextView>(R.id.session_empty)
        val content = findViewById<View>(R.id.session_content)
        val record = recorder.lastRecord()
        if (record == null || record.samples.isEmpty()) {
            empty.visibility = View.VISIBLE
            content.visibility = View.GONE
            return
        }
        empty.visibility = View.GONE
        content.visibility = View.VISIBLE

        val gameLabel = try {
            packageManager
                .getApplicationInfo(
                    record.packageName,
                    PackageManager.ApplicationInfoFlags.of(0)
                )
                .loadLabel(packageManager)
                .toString()
        } catch (e: PackageManager.NameNotFoundException) {
            record.packageName
        }
        val minutes = record.samples.size / 60
        val drain = (record.startLevel - record.endLevel).coerceAtLeast(0)
        findViewById<TextView>(R.id.session_header).text = gameLabel
        findViewById<TextView>(R.id.session_summary).text =
            getString(R.string.session_report_summary_fmt, minutes, drain)

        val fps = FloatArray(record.samples.size) { record.samples[it].fps }
        val headroom = FloatArray(record.samples.size) { record.samples[it].headroom }
        val power = FloatArray(record.samples.size) {
            val s = record.samples[it]
            abs(s.currentUa.toFloat()) * s.voltageMv / 1e9f
        }

        findViewById<SessionGraphView>(R.id.graph_fps).setSeries(
            fps, getString(R.string.session_graph_fps), "",
            Color.rgb(0x4c, 0xaf, 0x50), min = 0f
        )
        if (headroom.any { it.isFinite() }) {
            findViewById<SessionGraphView>(R.id.graph_thermal).setSeries(
                headroom, getString(R.string.session_graph_thermal), "",
                Color.rgb(0xff, 0x98, 0x00), min = 0f, max = 1.2f
            )
        } else {
            val temp = FloatArray(record.samples.size) { record.samples[it].battTemp }
            findViewById<SessionGraphView>(R.id.graph_thermal).setSeries(
                temp, getString(R.string.session_graph_temp), "\u00b0C",
                Color.rgb(0xff, 0x98, 0x00)
            )
        }
        findViewById<SessionGraphView>(R.id.graph_power).setSeries(
            power, getString(R.string.session_graph_power), "W",
            Color.rgb(0x21, 0x96, 0xf3)
        )

        val gpuLoad = FloatArray(record.samples.size) { record.samples[it].gpuLoad }
        val cpuFreq = FloatArray(record.samples.size) { record.samples[it].cpuMhz }
        val gpuFreq = FloatArray(record.samples.size) { record.samples[it].gpuMhz }
        bindGraph(
            R.id.graph_gpu_load, gpuLoad, R.string.session_graph_gpu_load, "%",
            Color.rgb(0xe9, 0x1e, 0x63), min = 0f, max = 100f
        )
        bindGraph(
            R.id.graph_cpu_freq, cpuFreq, R.string.session_graph_cpu_freq, "MHz",
            Color.rgb(0x9c, 0x27, 0xb0), min = 0f
        )
        bindGraph(
            R.id.graph_gpu_freq, gpuFreq, R.string.session_graph_gpu_freq, "MHz",
            Color.rgb(0x00, 0xbc, 0xd4), min = 0f
        )
    }

    private fun bindGraph(
        viewId: Int,
        data: FloatArray,
        labelRes: Int,
        unit: String,
        color: Int,
        min: Float = Float.NaN,
        max: Float = Float.NaN,
    ) {
        val view = findViewById<SessionGraphView>(viewId)
        if (data.none { it.isFinite() }) {
            view.visibility = View.GONE
            return
        }
        view.visibility = View.VISIBLE
        view.setSeries(data, getString(labelRes), unit, color, min, max)
    }
}
