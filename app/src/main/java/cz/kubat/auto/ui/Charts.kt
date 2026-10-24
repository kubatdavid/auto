package cz.kubat.auto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cz.kubat.auto.analysis.Band
import cz.kubat.auto.analysis.Row as Sample

class Series(val key: String, val label: String, val color: Color, val scale: Double = 1.0)

/** Time-series line chart over the trip (x = elapsed time, y = 0..yMax). */
@Composable
fun LineChart(rows: List<Sample>, series: List<Series>, yMax: Double, refY: Double? = null, yMin: Double = 0.0) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            for (s in series) {
                Box(Modifier.size(10.dp, 3.dp).background(s.color))
                Text(" ${s.label}   ", color = Palette.muted, fontSize = 12.sp)
            }
            Spacer(Modifier.weight(1f))
            Text("${yMin.toInt()}…${yMax.toInt()}", color = Palette.muted, fontSize = 11.sp)
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp).padding(top = 6.dp)) {
            if (rows.size < 2) return@Canvas
            val t0 = rows.first().elapsed
            val tMax = maxOf(60.0, rows.last().elapsed - t0)
            fun x(t: Double) = ((t - t0) / tMax * size.width).toFloat()
            fun y(v: Double) = (size.height - ((v.coerceIn(yMin, yMax) - yMin) / (yMax - yMin)) * size.height).toFloat()
            for (i in 0..4) {
                val gy = size.height * i / 4
                drawLine(Palette.line, Offset(0f, gy), Offset(size.width, gy), 1f)
            }
            if (refY != null) drawLine(Palette.ok, Offset(0f, y(refY)), Offset(size.width, y(refY)), 2f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            for (s in series) {
                val path = Path()
                var pen = false
                for (r in rows) {
                    val v = r[s.key]
                    if (v == null) { pen = false; continue }
                    val px = x(r.elapsed); val py = y(v / s.scale)
                    if (pen) path.lineTo(px, py) else path.moveTo(px, py)
                    pen = true
                }
                drawPath(path, s.color, style = Stroke(width = 3f))
            }
        }
        Text("${(rows.lastOrNull()?.elapsed?.minus(rows.first().elapsed) ?: 0.0).div(60).toInt()} min", color = Palette.muted, fontSize = 11.sp,
            modifier = Modifier.align(Alignment.End))
    }
}

/** L/100 km per speed band; best band green. */
@Composable
fun BandsChart(bands: List<Band>) {
    val vals = bands.mapNotNull { it.lp100 }
    if (vals.isEmpty()) return
    val yMax = maxOf(10.0, kotlin.math.ceil(vals.max() / 5) * 5)
    val best = bands.filter { it.lp100 != null && it.km >= 1 }.minByOrNull { it.lp100!! }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).background(Palette.panel, RoundedCornerShape(10.dp)).padding(10.dp)) {
        Text("Consumption by speed (L/100 km)", color = Palette.muted, fontSize = 12.sp)
        Row(Modifier.fillMaxWidth().height(150.dp).padding(top = 6.dp), verticalAlignment = Alignment.Bottom) {
            for (b in bands) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val lp = b.lp100
                    if (lp != null) {
                        Text("%.1f".format(lp), color = Palette.text, fontSize = 11.sp)
                        Box(Modifier.width(18.dp).height((100 * (lp / yMax)).dp)
                            .background(if (b === best) Palette.ok else Palette.warm))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            for (b in bands) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(b.label, color = Palette.muted, fontSize = 10.sp)
                    Text("%.1f km".format(b.km), color = Palette.muted, fontSize = 10.sp)
                }
            }
        }
    }
}
