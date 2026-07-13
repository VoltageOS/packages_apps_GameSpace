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
package io.chaldeaprjkt.gamespace.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import java.util.Locale

class SessionGraphView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.5f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(12f)
        color = resolveColor(android.R.attr.textColorPrimary)
    }
    private val statsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        textAlign = Paint.Align.RIGHT
        color = resolveColor(android.R.attr.textColorSecondary)
    }
    private val gridPaint = Paint().apply {
        strokeWidth = dp(0.5f)
        color = resolveColor(android.R.attr.textColorSecondary)
        alpha = 40
    }
    private val linePath = Path()

    private var values = floatArrayOf()
    private var label = ""
    private var unit = ""
    private var fixedMin = Float.NaN
    private var fixedMax = Float.NaN

    fun setSeries(
        data: FloatArray,
        label: String,
        unit: String,
        color: Int,
        min: Float = Float.NaN,
        max: Float = Float.NaN,
    ) {
        values = data
        this.label = label
        this.unit = unit
        linePaint.color = color
        fixedMin = min
        fixedMax = max
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = dp(24f)
        val bottom = height - dp(4f)
        val left = dp(4f)
        val right = width - dp(4f)
        canvas.drawText(label, left, dp(14f), labelPaint)
        val finite = values.filter { it.isFinite() }
        if (finite.isEmpty() || right - left < dp(16f)) return
        val lo = if (fixedMin.isNaN()) finite.min() else fixedMin
        var hi = if (fixedMax.isNaN()) finite.max() else fixedMax
        if (hi - lo < 1e-3f) hi = lo + 1f
        canvas.drawText(
            String.format(
                Locale.US, "min %.1f  avg %.1f  max %.1f %s",
                finite.min(), finite.average().toFloat(), finite.max(), unit
            ).trim(),
            right, dp(14f), statsPaint
        )
        for (i in 0..2) {
            val y = top + (bottom - top) * i / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        linePath.reset()
        var started = false
        val n = values.size
        for (i in 0 until n) {
            val v = values[i]
            if (!v.isFinite()) {
                started = false
                continue
            }
            val x = left + (right - left) * i.toFloat() / (n - 1).coerceAtLeast(1)
            val y = bottom - (bottom - top) * ((v - lo) / (hi - lo)).coerceIn(0f, 1f)
            if (!started) {
                linePath.moveTo(x, y)
                started = true
            } else {
                linePath.lineTo(x, y)
            }
        }
        canvas.drawPath(linePath, linePaint)
    }

    private fun dp(value: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics
    )

    private fun resolveColor(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return if (tv.resourceId != 0) context.getColor(tv.resourceId) else tv.data
    }
}
