package com.pixel.ruler

import android.app.Activity
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.view.*
import android.widget.*
import kotlin.math.abs

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, 1234)
        } else {
            startOverlayAndFinish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1234) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)) {
                startOverlayAndFinish()
            } else {
                Toast.makeText(this, "Permission overlay requise", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun startOverlayAndFinish() {
        startService(Intent(this, OverlayService::class.java))
        finish()
    }
}

data class RulerLine(val isHorizontal: Boolean, var pos: Float, var color: Int)

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var canvasView: RulerCanvasView
    private lateinit var panelLayout: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var coordsBox: LinearLayout
    private lateinit var coordsParams: WindowManager.LayoutParams
    private var isPanelRight = true
    private var areCoordsFloating = false

    private val prefs by lazy { getSharedPreferences("PixelRulerPrefs", Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        setupCanvas()
        setupCoordsBox()
        setupSidePanel()
        loadInitialLines()
    }

    private fun loadInitialLines() {
        val defX = prefs.getInt("default_x", 300).toFloat()
        val defY = prefs.getInt("default_y", 500).toFloat()
        val colorH = prefs.getInt("color_h", Color.RED)
        val colorV = prefs.getInt("color_v", Color.CYAN)

        canvasView.lines.add(RulerLine(true, defY, colorH))
        canvasView.lines.add(RulerLine(false, defX, colorV))
        canvasView.invalidate()
        updateCoordsDisplay()
    }

    private fun setupCanvas() {
        canvasView = RulerCanvasView(this) { updateCoordsDisplay() }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        wm.addView(canvasView, params)
    }

    private fun setupSidePanel() {
        panelLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC1A1A1A"))
            setPadding(12, 20, 12, 20)
        }

        val buttons = listOf(
            "Off" to { stopSelf() },
            "+ H" to {
                val c = prefs.getInt("color_h", Color.RED)
                val y = prefs.getInt("default_y", 400).toFloat() + (canvasView.lines.size * 30)
                canvasView.lines.add(RulerLine(true, y, c))
                canvasView.selectedLine = canvasView.lines.last()
                canvasView.invalidate()
                updateCoordsDisplay()
            },
            "+ V" to {
                val c = prefs.getInt("color_v", Color.CYAN)
                val x = prefs.getInt("default_x", 300).toFloat() + (canvasView.lines.size * 30)
                canvasView.lines.add(RulerLine(false, x, c))
                canvasView.selectedLine = canvasView.lines.last()
                canvasView.invalidate()
                updateCoordsDisplay()
            },
            "-" to {
                if (canvasView.selectedLine != null) {
                    canvasView.lines.remove(canvasView.selectedLine)
                    canvasView.selectedLine = canvasView.lines.lastOrNull()
                } else if (canvasView.lines.isNotEmpty()) {
                    canvasView.lines.removeAt(canvasView.lines.lastIndex)
                }
                canvasView.invalidate()
                updateCoordsDisplay()
            },
            "Other side" to {
                isPanelRight = !isPanelRight
                panelParams.gravity = (if (isPanelRight) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
                wm.updateViewLayout(panelLayout, panelParams)
                if (!areCoordsFloating) dockCoordsBox()
            },
            "Paramètres" to { openSettingsDialog() }
        )

        buttons.forEach { (label, action) ->
            val btn = Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(if (label == "Off") Color.parseColor("#99CC0000") else Color.parseColor("#44FFFFFF"))
                val lp = LinearLayout.LayoutParams(170, 95).apply { setMargins(0, 6, 0, 6) }
                layoutParams = lp
                setOnClickListener { action() }
            }
            panelLayout.addView(btn)
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
        }
        wm.addView(panelLayout, panelParams)
    }

    private fun setupCoordsBox() {
        coordsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD000000"))
            setPadding(16, 12, 16, 12)
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE

        coordsParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 100
        }

        coordsBox.setOnTouchListener(object : View.OnTouchListener {
            var initX = 0; var initY = 0; var touchX = 0f; var touchY = 0f
            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        areCoordsFloating = true
                        initX = coordsParams.x; initY = coordsParams.y
                        touchX = event.rawX; touchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        coordsParams.x = initX + (event.rawX - touchX).toInt()
                        coordsParams.y = initY + (event.rawY - touchY).toInt()
                        wm.updateViewLayout(coordsBox, coordsParams)
                        return true
                    }
                }
                return false
            }
        })

        wm.addView(coordsBox, coordsParams)
    }

    private fun dockCoordsBox() {
        areCoordsFloating = false
        coordsParams.x = if (isPanelRight) 20 else 220
        coordsParams.y = 100
        wm.updateViewLayout(coordsBox, coordsParams)
    }

    private fun updateCoordsDisplay() {
        coordsBox.removeAllViews()
        val header = TextView(this).apply {
            text = if (areCoordsFloating) "📌 Coords (Ranger)" else "📐 Coords (Glisser)"
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setOnClickListener { dockCoordsBox() }
        }
        coordsBox.addView(header)

        val hLines = canvasView.lines.filter { it.isHorizontal }
        val vLines = canvasView.lines.filter { !it.isHorizontal }

        if (hLines.isEmpty() || vLines.isEmpty()) {
            val info = TextView(this).apply {
                text = "Ajoutez 1 H et 1 V"
                textSize = 11f
                setTextColor(Color.GRAY)
            }
            coordsBox.addView(info)
            return
        }

        for (v in vLines) {
            for (h in hLines) {
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val tvX = TextView(this).apply {
                    text = "X:${v.pos.toInt()} "
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(v.color)
                }
                val tvY = TextView(this).apply {
                    text = "Y:${h.pos.toInt()}"
                    textSize = 13f
setTypeface(null, Typeface.BOLD)
setTextColor(h.color)
}
row.addView(tvX)
row.addView(tvY)
coordsBox.addView(row)
}
}
}
private fun openSettingsDialog() {
val layout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setPadding(40, 30, 40, 20)
}
val inputX = EditText(this).apply {
hint = "Valeur X de base (px)"
inputType = InputType.TYPE_CLASS_NUMBER
setText(prefs.getInt("default_x", 300).toString())
}
val inputY = EditText(this).apply {
hint = "Valeur Y de base (px)"
inputType = InputType.TYPE_CLASS_NUMBER
setText(prefs.getInt("default_y", 500).toString())
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
val lblSel = TextView(this).apply { text = "Couleur de la ligne sélectionnée :" }
val spinSel = Spinner(this).apply {
adapter = ArrayAdapter(this@OverlayService, android.R.layout.simple_spinner_dropdown_item, colorNames)
val curC = canvasView.selectedLine?.color ?: Color.YELLOW
setSelection(colors.values.indexOf(curC).coerceAtLeast(0))
}
layout.addView(TextView(this).apply { text = "Coordonnées de base (X / Y) :" })
layout.addView(inputX)
layout.addView(inputY)
layout.addView(lblH)
layout.addView(spinH)
layout.addView(lblV)
layout.addView(spinV)
if (canvasView.selectedLine != null) {
layout.addView(lblSel)
layout.addView(spinSel)
}
val dialog = AlertDialog.Builder(this)
.setTitle("Paramètres de précision")
.setView(layout)
.setPositiveButton("Enregistrer") { _, _ ->
val newX = inputX.text.toString().toIntOrNull() ?: 300
val newY = inputY.text.toString().toIntOrNull() ?: 500
val chosenH = colors[colorNames[spinH.selectedItemPosition]] ?: Color.RED
val chosenV = colors[colorNames[spinV.selectedItemPosition]] ?: Color.CYAN
prefs.edit()
.putInt("default_x", newX)
.putInt("default_y", newY)
.putInt("color_h", chosenH)
.putInt("color_v", chosenV)
.apply()
canvasView.selectedLine?.let { line ->
line.color = colors[colorNames[spinSel.selectedItemPosition]] ?: line.color
line.pos = if (line.isHorizontal) newY.toFloat() else newX.toFloat()
}
canvasView.invalidate()
updateCoordsDisplay()
Toast.makeText(this, "Paramètres sauvegardés", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("Annuler", null)
.create()
val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
dialog.window?.setType(type)
dialog.show()
}
override fun onDestroy() {
super.onDestroy()
if (::canvasView.isInitialized) wm.removeView(canvasView)
if (::panelLayout.isInitialized) wm.removeView(panelLayout)
if (::coordsBox.isInitialized) wm.removeView(coordsBox)
}
}
class RulerCanvasView(context: Context, val onUpdate: () -> Unit) : View(context) {
val lines = mutableListOf()
var selectedLine: RulerLine? = null
private var isDragging = false
private val linePaint = Paint().apply {
strokeWidth = 1f // Strictement 1 pixel d'épaisseur
isAntiAlias = false
}
private val btnPaint = Paint().apply {
color = Color.parseColor("#DD222222")
style = Paint.Style.FILL
}
private val arrowPaint = Paint().apply {
color = Color.WHITE
textSize = 36f
textAlign = Paint.Align.CENTER
isFakeBoldText = true
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
// Flèches de précision 1px sur la ligne sélectionnée
selectedLine?.let { line ->
if (line.isHorizontal) {
val cx = width / 2f
val yUp = (line.pos - 75f).coerceAtLeast(50f)
val yDown = (line.pos + 75f).coerceAtMost(height - 50f)
canvas.drawCircle(cx, yUp, 38f, btnPaint)
canvas.drawText("▲", cx, yUp + 12f, arrowPaint)
canvas.drawCircle(cx, yDown, 38f, btnPaint)
canvas.drawText("▼", cx, yDown + 12f, arrowPaint)
} else {
val cy = height / 2f
val xLeft = (line.pos - 75f).coerceAtLeast(50f)
val xRight = (line.pos + 75f).coerceAtMost(width - 50f)
canvas.drawCircle(xLeft, cy, 38f, btnPaint)
canvas.drawText("◀", xLeft, cy + 12f, arrowPaint)
canvas.drawCircle(xRight, cy, 38f, btnPaint)
canvas.drawText("▶", xRight, cy + 12f, arrowPaint)
}
}
}
override fun onTouchEvent(event: MotionEvent): Boolean {
val x = event.x
val y = event.y
if (event.action == MotionEvent.ACTION_DOWN) {
// 1. Vérifier clic sur les flèches de précision 1px
selectedLine?.let { line ->
if (line.isHorizontal) {
val cx = width / 2f
val yUp = (line.pos - 75f).coerceAtLeast(50f)
val yDown = (line.pos + 75f).coerceAtMost(height - 50f)
if (abs(x - cx) < 50f && abs(y - yUp) < 50f) {
line.pos = (line.pos - 1f).coerceAtLeast(0f)
invalidate(); onUpdate(); return true
}
if (abs(x - cx) < 50f && abs(y - yDown) < 50f) {
line.pos = (line.pos + 1f).coerceAtMost(height.toFloat())
invalidate(); onUpdate(); return true
}
} else {
val cy = height / 2f
val xLeft = (line.pos - 75f).coerceAtLeast(50f)
val xRight = (line.pos + 75f).coerceAtMost(width - 50f)
if (abs(x - xLeft) < 50f && abs(y - cy) < 50f) {
line.pos = (line.pos - 1f).coerceAtLeast(0f)
invalidate(); onUpdate(); return true
}
if (abs(x - xRight) < 50f && abs(y - cy) < 50f) {
line.pos = (line.pos + 1f).coerceAtMost(width.toFloat())
invalidate(); onUpdate(); return true
}
}
}
// 2. Sélectionner une ligne proche du doigt
val hit = lines.minByOrNull { if (it.isHorizontal) abs(it.pos - y) else abs(it.pos - x) }
val dist = hit?.let { if (it.isHorizontal) abs(it.pos - y) else abs(it.pos - x) } ?: 999f
if (hit != null && dist < 45f) {
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
onUpdate()
}
return true
}
if (event.action == MotionEvent.ACTION_UP) {
isDragging = false
}
return false
}
}
