package com.pixel.ruler

import android.app.Activity
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import kotlin.math.abs

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, 1001)
        } else {
            launchOverlayDirectly()
        }
    }

    private fun launchOverlayDirectly() {
        val serviceIntent = Intent(this, OverlayService::class.java).apply {
            action = "ACTION_START_OR_SHOW"
        }
        startService(serviceIntent)
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                launchOverlayDirectly()
            } else {
                Toast.makeText(this, "Permission overlay requise", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }
}

data class RulerLine(val isHorizontal: Boolean, var pos: Float, var color: Int)

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var canvasView: RulerCanvasView
    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var panelLayout: LinearLayout
    private lateinit var buttonsContainer: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams

    private var collapseBtnRef: Button? = null
    private var viewsAdded = false
    private var isPanelRight = true
    private var isPanelCollapsed = false
    private var isUnderneathTouchOn = false

    private var screenW = 1080
    private var screenH = 2400

    private val prefs by lazy { getSharedPreferences("PixelRulerPrefs", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        screenW = metrics.widthPixels
        screenH = metrics.heightPixels

        initAllViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!viewsAdded) {
            initAllViews()
        }
        return START_NOT_STICKY
    }

    private fun initAllViews() {
        if (viewsAdded) return
        setupCanvas()
        setupSidePanel()
        loadInitialLines()
        viewsAdded = true
    }

    private fun createRoundedBg(color: Int, radiusPx: Float, strokeColor: Int = Color.TRANSPARENT): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(color)
            if (strokeColor != Color.TRANSPARENT) {
                setStroke(2, strokeColor)
            }
        }
    }

    private fun loadInitialLines() {
        val defaultCenterX = (screenW * 0.42f).toFloat()
        val defaultCenterY = (screenH * 0.45f).toFloat()
        val colorH = prefs.getInt("color_h", Color.RED)
        val colorV = prefs.getInt("color_v", Color.CYAN)

        canvasView.baseW = prefs.getInt("base_w", screenW)
        canvasView.baseH = prefs.getInt("base_h", screenH)
        canvasView.screenW = screenW
        canvasView.screenH = screenH

        canvasView.addLine(RulerLine(true, defaultCenterY, colorH))
        canvasView.addLine(RulerLine(false, defaultCenterX, colorV))
        canvasView.selectedLine = canvasView.getAllLines().firstOrNull()
        canvasView.invalidate()
    }

    private fun setupCanvas() {
        canvasView = RulerCanvasView(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        canvasParams = WindowManager.LayoutParams(
            screenW,
            screenH,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(canvasView, canvasParams)
    }

    private fun toggleUnderneathTouch(btn: Button) {
        isUnderneathTouchOn = !isUnderneathTouchOn
        if (isUnderneathTouchOn) {
            canvasParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            btn.text = "Tactile: ON"
            btn.background = createRoundedBg(Color.parseColor("#CC16A34A"), 20f)
        } else {
            canvasParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            btn.text = "Tactile: OFF"
            btn.background = createRoundedBg(Color.parseColor("#66475569"), 20f)
        }
        wm.updateViewLayout(canvasView, canvasParams)
        canvasView.showArrows = !isUnderneathTouchOn
        canvasView.invalidate()
    }

    private fun setupSidePanel() {
        panelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = createRoundedBg(Color.parseColor("#E6111827"), 32f, Color.parseColor("#44FFFFFF"))
            setPadding(16, 16, 16, 16)
            elevation = 12f
        }

        val collapseBtn = Button(this).apply {
            text = "Masquer ▸"
            textSize = 11f
            isAllCaps = false
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#CBD5E1"))
            background = createRoundedBg(Color.parseColor("#33FFFFFF"), 18f)
            setPadding(8, 0, 8, 0)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(235, 72).apply { setMargins(0, 0, 0, 8) }
            setOnClickListener { toggleSidebarCollapse() }
        }
        collapseBtnRef = collapseBtn
        panelLayout.addView(collapseBtn)

        buttonsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panelLayout.addView(buttonsContainer)

        lateinit var touchBtn: Button

        val buttons = listOf(
            "Off" to { cleanupAndStop() },
            "Tactile: OFF" to { toggleUnderneathTouch(touchBtn) },
            "+ H" to {
                val c = prefs.getInt("color_h", Color.RED)
                val y = (screenH * 0.35f) + (canvasView.lineCount() * 40f)
                val newLine = RulerLine(true, y.coerceAtMost(screenH - 120f), c)
                canvasView.addLine(newLine)
                canvasView.selectedLine = newLine
                canvasView.invalidate()
            },
            "+ V" to {
                val c = prefs.getInt("color_v", Color.CYAN)
                val x = (screenW * 0.35f) + (canvasView.lineCount() * 40f)
                val newLine = RulerLine(false, x.coerceAtMost(screenW - 120f), c)
                canvasView.addLine(newLine)
                canvasView.selectedLine = newLine
                canvasView.invalidate()
            },
            "-" to {
                canvasView.removeSelectedOrLast()
                canvasView.invalidate()
            },
            "Other side" to {
                isPanelRight = !isPanelRight
                panelParams.gravity = (if (isPanelRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                updateCollapseButtonLabel()
                wm.updateViewLayout(panelLayout, panelParams)
            },
            "Paramètres" to { openSettingsDialog() }
        )

        buttons.forEach { (label, action) ->
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.WHITE)
                setPadding(10, 0, 10, 0)
                gravity = Gravity.CENTER
                background = when (label) {
                    "Off" -> createRoundedBg(Color.parseColor("#CCDC2626"), 20f)
                    "Tactile: OFF" -> createRoundedBg(Color.parseColor("#66475569"), 20f)
                    else -> createRoundedBg(Color.parseColor("#33FFFFFF"), 20f)
                }
                val lp = LinearLayout.LayoutParams(235, 84).apply { setMargins(0, 6, 0, 6) }
                layoutParams = lp
                setOnClickListener { action() }
            }
            if (label == "Tactile: OFF") touchBtn = btn
            buttonsContainer.addView(btn)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
panelParams = WindowManager.LayoutParams(
WindowManager.LayoutParams.WRAP_CONTENT,
WindowManager.LayoutParams.WRAP_CONTENT,
type,
WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
PixelFormat.TRANSLUCENT
).apply {
gravity = Gravity.END or Gravity.CENTER_VERTICAL
y = 220
}
wm.addView(panelLayout, panelParams)
}
private fun toggleSidebarCollapse() {
isPanelCollapsed = !isPanelCollapsed
buttonsContainer.visibility = if (isPanelCollapsed) View.GONE else View.VISIBLE
collapseBtnRef?.layoutParams = LinearLayout.LayoutParams(
if (isPanelCollapsed) 95 else 235,
84
).apply { setMargins(0, 0, 0, if (isPanelCollapsed) 0 else 8) }
updateCollapseButtonLabel()
wm.updateViewLayout(panelLayout, panelParams)
}
private fun updateCollapseButtonLabel() {
collapseBtnRef?.text = if (isPanelCollapsed) {
if (isPanelRight) "◂" else "▸"
} else {
if (isPanelRight) "Masquer ▸" else "◂ Masquer"
}
}
private fun openSettingsDialog() {
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(40, 30, 40, 20)
}
val inputW = EditText(this).apply {
hint = "Largeur X écran de base (px)"
inputType = InputType.TYPE_CLASS_NUMBER
setText(prefs.getInt("base_w", screenW).toString())
}
val inputH = EditText(this).apply {
hint = "Hauteur Y écran de base (px)"
inputType = InputType.TYPE_CLASS_NUMBER
setText(prefs.getInt("base_h", screenH).toString())
}
val colors = mapOf(
"Rouge" to Color.RED,
"Cyan" to Color.CYAN,
"Vert Fluo" to Color.GREEN,
"Jaune" to Color.YELLOW,
"Magenta" to Color.MAGENTA,
"Blanc" to Color.WHITE
)
val colorNames = colors.keys.toTypedArray()
val lblH = TextView(this).apply { text = "Couleur lignes Horizontales (Y) :" }
val spinH = Spinner(this).apply {
adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_dropdown_item, colorNames)
val savedH = prefs.getInt("color_h", Color.RED)
setSelection(colors.values.indexOf(savedH).coerceAtLeast(0))
}
val lblV = TextView(this).apply { text = "Couleur lignes Verticales (X) :" }
val spinV = Spinner(this).apply {
adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_dropdown_item, colorNames)
val savedV = prefs.getInt("color_v", Color.CYAN)
setSelection(colors.values.indexOf(savedV).coerceAtLeast(0))
}
layout.addView(TextView(this).apply { text = "Taille d'écran de base (Largeur X / Hauteur Y) :" })
layout.addView(inputW)
layout.addView(inputH)
layout.addView(lblH)
layout.addView(spinH)
layout.addView(lblV)
layout.addView(spinV)
val dialog = AlertDialog.Builder(this)
.setTitle("Paramètres de précision")
.setView(layout)
.setPositiveButton("Enregistrer") { _, _ ->
val newW = (inputW.text.toString().toIntOrNull() ?: screenW).coerceAtLeast(1)
val newH = (inputH.text.toString().toIntOrNull() ?: screenH).coerceAtLeast(1)
val chosenH = colors[colorNames[spinH.selectedItemPosition]] ?: Color.RED
val chosenV = colors[colorNames[spinV.selectedItemPosition]] ?: Color.CYAN
prefs.edit()
.putInt("base_w", newW)
.putInt("base_h", newH)
.putInt("color_h", chosenH)
.putInt("color_v", chosenV)
.apply()
canvasView.baseW = newW
canvasView.baseH = newH
for (line in canvasView.getAllLines()) {
line.color = if (line.isHorizontal) chosenH else chosenV
}
canvasView.invalidate()
Toast.makeText(this, "Paramètres sauvegardés ($newW×$newH)", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("Annuler", null)
.create()
val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
dialog.window?.setType(type)
dialog.show()
}
private fun cleanupAndStop() {
if (viewsAdded) {
try { wm.removeViewImmediate(canvasView) } catch (e: Exception) {}
try { wm.removeViewImmediate(panelLayout) } catch (e: Exception) {}
viewsAdded = false
}
stopSelf()
}
override fun onDestroy() {
cleanupAndStop()
super.onDestroy()
}
}
class RulerCanvasView(context: Context) : View(context) {
private val lines = ArrayList()
var selectedLine: RulerLine? = null
var showArrows = true
var baseW = 1080
var baseH = 2400
var screenW = 1080
var screenH = 2400
private var isDragging = false
fun addLine(line: RulerLine) { lines.add(line) }
fun lineCount(): Int = lines.size
fun getAllLines(): Array = lines.toTypedArray()
fun removeSelectedOrLast() {
val sel = selectedLine
if (sel != null) {
lines.remove(sel)
selectedLine = lines.lastOrNull()
} else if (lines.isNotEmpty()) {
lines.removeAt(lines.lastIndex)
}
}
private val linePaint = Paint().apply {
strokeWidth = 1f
isAntiAlias = false
}
private val btnPaint = Paint().apply {
color = Color.parseColor("#DD1E293B")
style = Paint.Style.FILL
isAntiAlias = true
}
private val arrowPaint = Paint().apply {
color = Color.WHITE
textSize = 34f
textAlign = Paint.Align.CENTER
isFakeBoldText = true
isAntiAlias = true
}
private val tagBgPaint = Paint().apply {
color = Color.parseColor("#CC0F172A")
style = Paint.Style.FILL
isAntiAlias = true
}
private val tagTextPaint = Paint().apply {
textSize = 23f
isFakeBoldText = true
isAntiAlias = true
}
override fun onDraw(canvas: Canvas) {
super.onDraw(canvas)
for (line in lines) {
linePaint.color = line.color
if (line.isHorizontal) {
canvas.drawLine(0f, line.pos, width.toFloat(), line.pos, linePaint)
} else {
canvas.drawLine(line.pos, 0f, line.pos, height.toFloat(), linePaint)
}
}
for (v in lines) {
if (v.isHorizontal) continue
for (h in lines) {
if (!h.isHorizontal) continue
val sx = (v.pos * baseW / screenW.toFloat()).toInt()
val sy = (h.pos * baseH / screenH.toFloat()).toInt()
val txtX = "X:$sx "
val txtY = "Y:$sy"
val wX = tagTextPaint.measureText(txtX)
val wY = tagTextPaint.measureText(txtY)
val totalW = wX + wY + 18f
val boxH = 34f
val drawX = if (v.pos + totalW + 14f < width) v.pos + 10f else (v.pos - totalW - 10f).coerceAtLeast(6f)
val drawY = if (h.pos - boxH - 10f > 40f) h.pos - boxH - 8f else (h.pos + 10f).coerceAtMost(height - boxH - 6f)
canvas.drawRoundRect(drawX, drawY, drawX + totalW, drawY + boxH, 10f, 10f, tagBgPaint)
tagTextPaint.color = v.color
canvas.drawText(txtX, drawX + 9f, drawY + 24f, tagTextPaint)
tagTextPaint.color = h.color
canvas.drawText(txtY, drawX + 9f + wX, drawY + 24f, tagTextPaint)
}
}
if (!showArrows) return
selectedLine?.let { line ->
if (line.isHorizontal) {
val cx = width / 2f
val yUp = (line.pos - 75f).coerceAtLeast(50f)
val yDown = (line.pos + 75f).coerceAtMost(height - 50f)
canvas.drawCircle(cx, yUp, 36f, btnPaint)
canvas.drawText("▲", cx, yUp + 11f, arrowPaint)
canvas.drawCircle(cx, yDown, 36f, btnPaint)
canvas.drawText("▼", cx, yDown + 11f, arrowPaint)
} else {
val cy = height / 2f
val xLeft = (line.pos - 75f).coerceAtLeast(50f)
val xRight = (line.pos + 75f).coerceAtMost(width - 50f)
canvas.drawCircle(xLeft, cy, 36f, btnPaint)
canvas.drawText("◀", xLeft, cy + 11f, arrowPaint)
canvas.drawCircle(xRight, cy, 36f, btnPaint)
canvas.drawText("▶", xRight, cy + 11f, arrowPaint)
}
}
}
override fun onTouchEvent(event: MotionEvent): Boolean {
val x = event.x
val y = event.y
if (event.action == MotionEvent.ACTION_DOWN) {
selectedLine?.let { line ->
if (line.isHorizontal) {
val cx = width / 2f
val yUp = (line.pos - 75f).coerceAtLeast(50f)
val yDown = (line.pos + 75f).coerceAtMost(height - 50f)
if (abs(x - cx) < 55f && abs(y - yUp) < 55f) {
line.pos = (line.pos - 1f).coerceAtLeast(0f)
invalidate(); return true
}
if (abs(x - cx) < 55f && abs(y - yDown) < 55f) {
line.pos = (line.pos + 1f).coerceAtMost(height.toFloat())
invalidate(); return true
}
} else {
val cy = height / 2f
val xLeft = (line.pos - 75f).coerceAtLeast(50f)
val xRight = (line.pos + 75f).coerceAtMost(width - 50f)
if (abs(x - xLeft) < 55f && abs(y - cy) < 55f) {
line.pos = (line.pos - 1f).coerceAtLeast(0f)
invalidate(); return true
}
if (abs(x - xRight) < 55f && abs(y - cy) < 55f) {
line.pos = (line.pos + 1f).coerceAtMost(width.toFloat())
invalidate(); return true
}
}
}
var hit: RulerLine? = null
var minDist = 999f
for (line in lines) {
val d = if (line.isHorizontal) abs(line.pos - y) else abs(line.pos - x)
if (d < minDist) {
minDist = d
hit = line
}
}
if (hit != null && minDist < 70f) {
selectedLine = hit
isDragging = true
invalidate()
return true
} else {
selectedLine = null
invalidate()
return false
}
}
if (event.action == MotionEvent.ACTION_MOVE && isDragging) {
selectedLine?.let {
it.pos = if (it.isHorizontal) y.coerceIn(0f, height.toFloat()) else x.coerceIn(0f, width.toFloat())
invalidate()
}
return true
}
if (event.action == MotionEvent.ACTION_UP) {
isDragging = false
}
return false
}
}
