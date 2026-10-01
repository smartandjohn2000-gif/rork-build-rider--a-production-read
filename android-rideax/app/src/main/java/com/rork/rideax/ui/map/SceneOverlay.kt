package com.rork.rideax.ui.map

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.MotionEvent
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import com.rork.rideax.R
import com.rork.rideax.model.GeoPoint
import com.rork.rideax.ui.theme.RideColors
import org.osmdroid.views.MapView
import org.osmdroid.views.Projection
import org.osmdroid.views.overlay.Overlay
import kotlin.math.hypot
import org.osmdroid.util.GeoPoint as OsmPoint

/**
 * Draws routes, pins, idle cars and the live vehicle directly on the map
 * canvas. One overlay keeps draw order explicit and avoids per-marker views.
 */
class SceneOverlay(context: Context, private val onGesture: () -> Unit) : Overlay() {
    var scene: MapScene = MapScene()

    private val d = context.resources.displayMetrics.density
    private val car: Drawable = requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_map_car)).mutate()

    private val accent = RideColors.Accent.toArgb()
    private val canvasColor = RideColors.Canvas.toArgb()
    private val surface = RideColors.Surface.toArgb()
    private val raised = RideColors.SurfaceRaised.toArgb()
    private val textPrimary = RideColors.TextPrimary.toArgb()
    private val textSecondary = RideColors.TextSecondary.toArgb()

    private val routeHalo = stroke(9f, 0x66000000)
    private val routePaint = stroke(5f, accent)
    private val drivenPaint = stroke(5f, 0xFF4A5672.toInt())
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val zoneStroke = stroke(1.5f, accent).apply {
        alpha = 140
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 5 * d), 0f)
    }
    private val captionText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textPrimary
        textSize = 11.5f * d
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val conePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var downX = 0f
    private var downY = 0f
    private var reported = false

    private fun stroke(widthDp: Float, color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthDp * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }

    private fun Projection.px(point: GeoPoint): Point = toPixels(OsmPoint(point.latitude, point.longitude), null)

    override fun draw(canvas: Canvas, projection: Projection) {
        val current = scene
        current.zone?.let { drawZone(canvas, projection, it) }
        drawPolyline(canvas, projection, current.drivenRoute, drivenPaint, halo = false)
        drawPolyline(canvas, projection, current.route, routePaint, halo = true)
        current.nearbyCars.forEach { drawIdleCar(canvas, projection.px(it)) }
        current.pins.forEach { pin ->
            val p = projection.px(pin.point)
            if (pin.kind == PinKind.PICKUP) drawPickup(canvas, p) else drawDropoff(canvas, p)
        }
        current.vehicle?.let { drawVehicle(canvas, projection.px(it.point), it) }
    }

    private fun drawPolyline(canvas: Canvas, projection: Projection, points: List<GeoPoint>, paint: Paint, halo: Boolean) {
        if (points.size < 2) return
        val path = Path()
        points.forEachIndexed { index, point ->
            val p = projection.px(point)
            if (index == 0) path.moveTo(p.x.toFloat(), p.y.toFloat()) else path.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        if (halo) canvas.drawPath(path, routeHalo)
        canvas.drawPath(path, paint)
    }

    private fun drawZone(canvas: Canvas, projection: Projection, zone: PickupZone) {
        val p = projection.px(zone.center)
        val radius = projection.metersToPixels(zone.radiusMeters.toFloat())
        fill.color = accent; fill.alpha = 30
        canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), radius, fill)
        canvas.drawCircle(p.x.toFloat(), p.y.toFloat(), radius, zoneStroke)
        fill.alpha = 255
    }

    private fun drawPickup(canvas: Canvas, p: Point) {
        val x = p.x.toFloat(); val y = p.y.toFloat()
        fill.color = accent; fill.alpha = 50
        canvas.drawCircle(x, y, 18 * d, fill)
        fill.alpha = 255
        canvas.drawCircle(x, y, 10 * d, fill)
        fill.color = canvasColor
        canvas.drawCircle(x, y, 4 * d, fill)
    }

    private fun drawDropoff(canvas: Canvas, p: Point) {
        val x = p.x.toFloat(); val y = p.y.toFloat()
        val half = 9 * d
        fill.color = 0x55000000
        canvas.drawRoundRect(RectF(x - half - 2 * d, y - half - 2 * d, x + half + 2 * d, y + half + 2 * d), 4 * d, 4 * d, fill)
        fill.color = textPrimary
        canvas.drawRoundRect(RectF(x - half, y - half, x + half, y + half), 3 * d, 3 * d, fill)
        fill.color = canvasColor
        val inner = 3.5f * d
        canvas.drawRect(x - inner, y - inner, x + inner, y + inner, fill)
    }

    private fun drawIdleCar(canvas: Canvas, p: Point) {
        val x = p.x.toFloat(); val y = p.y.toFloat()
        fill.color = raised
        canvas.drawCircle(x, y, 12 * d, fill)
        outline.color = 0x22FFFFFF; outline.strokeWidth = 1 * d
        canvas.drawCircle(x, y, 12 * d, outline)
        drawCar(canvas, x, y, 14 * d, textSecondary)
    }

    private fun drawVehicle(canvas: Canvas, p: Point, vehicle: VehicleMarker) {
        val x = p.x.toFloat(); val y = p.y.toFloat()

        // Direction of travel lives in the cone; the car itself stays upright.
        canvas.save()
        canvas.rotate(vehicle.heading.toFloat(), x, y)
        val tip = y - 40 * d
        conePaint.shader = LinearGradient(x, y, x, tip, accent and 0x00FFFFFF or (0x99 shl 24), accent and 0x00FFFFFF, Shader.TileMode.CLAMP)
        val cone = Path().apply {
            moveTo(x - 7 * d, y - 10 * d)
            lineTo(x - 15 * d, tip)
            quadTo(x, tip - 6 * d, x + 15 * d, tip)
            lineTo(x + 7 * d, y - 10 * d)
            close()
        }
        canvas.drawPath(cone, conePaint)
        canvas.restore()

        fill.color = 0x66000000
        canvas.drawCircle(x, y + 1.5f * d, 19 * d, fill)
        fill.color = canvasColor
        canvas.drawCircle(x, y, 18 * d, fill)
        outline.color = accent; outline.strokeWidth = 2.5f * d
        canvas.drawCircle(x, y, 18 * d, outline)
        drawCar(canvas, x, y, 21 * d, textPrimary)

        vehicle.caption?.let { caption ->
            val width = captionText.measureText(caption) + 18 * d
            val height = 22 * d
            val top = y - 18 * d - 10 * d - height
            val rect = RectF(x - width / 2, top, x + width / 2, top + height)
            fill.color = 0x55000000
            canvas.drawRoundRect(RectF(rect.left, rect.top + 2 * d, rect.right, rect.bottom + 2 * d), height / 2, height / 2, fill)
            fill.color = surface
            canvas.drawRoundRect(rect, height / 2, height / 2, fill)
            outline.color = 0x22FFFFFF; outline.strokeWidth = 1 * d
            canvas.drawRoundRect(rect, height / 2, height / 2, outline)
            val baseline = rect.centerY() - (captionText.descent() + captionText.ascent()) / 2
            canvas.drawText(caption, x, baseline, captionText)
        }
    }

    private fun drawCar(canvas: Canvas, x: Float, y: Float, size: Float, color: Int) {
        val half = (size / 2).toInt()
        car.setTint(color)
        car.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
        car.draw(canvas)
    }

    /** Reports the rider panning or pinching so the camera stops following. */
    override fun onTouchEvent(event: MotionEvent, mapView: MapView): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; reported = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> report()
            MotionEvent.ACTION_MOVE -> if (hypot(event.x - downX, event.y - downY) > 14 * d) report()
        }
        return false
    }

    private fun report() {
        if (reported) return
        reported = true
        onGesture()
    }
}
