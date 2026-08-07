package com.mttd.ui.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import com.mttd.domain.models.TimeSample

/**
 * 간단한 스파크라인. 누적 수익 시계열을 Canvas 로 렌더.
 *
 * - X 축: 시간 (samples 인덱스 기반, 등간격)
 * - Y 축: 누적 값 (min~max 정규화)
 * - 값이 단조 증가라 대체로 우상향 곡선
 */
@Composable
fun Sparkline(
    samples: List<TimeSample>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color(0xFF34D399),
    fillColor: Color = Color(0xFF34D399).copy(alpha = 0.2f),
) {
    Canvas(modifier = modifier) {
        if (samples.size < 2) return@Canvas

        val values = samples.map { it.cumulativeValue }
        val minV = values.min()
        val maxV = values.max().coerceAtLeast(minV + 1e-6)
        val range = maxV - minV
        val w = size.width
        val h = size.height
        val stepX = w / (values.size - 1)

        fun x(i: Int) = i * stepX
        fun y(v: Double) = h - ((v - minV) / range).toFloat() * h

        // 채우기: line + baseline
        val fillPath = Path().apply {
            moveTo(0f, h)
            for (i in values.indices) lineTo(x(i), y(values[i]))
            lineTo(w, h)
            close()
        }
        drawPath(fillPath, color = fillColor)

        // 라인 그리기
        val linePath = Path().apply {
            moveTo(0f, y(values[0]))
            for (i in 1 until values.size) lineTo(x(i), y(values[i]))
        }
        drawPath(linePath, color = lineColor, style = Stroke(width = 2f))

        // 마지막 점 강조
        val lastX = x(values.size - 1)
        val lastY = y(values.last())
        drawCircle(color = lineColor, radius = 3f, center = Offset(lastX, lastY))
    }
}
