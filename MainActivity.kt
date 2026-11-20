package com.pixel.ruler

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.util.DisplayMetrics
import android.view.*
import android.widget.*
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

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

class LoupePermissionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(mpm.createScreenCaptureIntent(), 2002)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2002 && resultCode == RESULT_OK && data != null) {
            val serviceIntent = Intent(this, OverlayService::class.java).apply {
                action = "ACTION_INIT_LOUPE"
                putExtra("RESULT_CODE", resultCode)
                putExtra("DATA", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } else {
            Toast.makeText(this, "Autorisation écran refusée pour la loupe", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}

data class RulerLine(val isHorizontal: Boolean, var pos: Float, var color: Int)

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var canvasView: RulerCanvasView
    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var panelLayout: LinearLayout
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var coordsBox: LinearLayout
    private lateinit var coordsParams: WindowManager.LayoutParams
    private lateinit var loupeView: RoundLoupeView
    private lateinit var loupeParams: WindowManager.LayoutParams

    private var loupeBtnRef: Button? = null
    private var viewsAdded = false
    private var isPanelRight = true
    private var areCoordsFloating = false
    private var isLoupeActive = false
    private var isTouchEnabled = true

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var screenBitmap: Bitmap? = null
    private var screenW = 1080
    private var screenH = 2400
    private var screenDpi = 420
    private val mainHandler = Handler(Looper.getMainLooper())

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
        screenDpi = metrics.densityDpi

        initAllViews()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!viewsAdded) {
            initAllViews()
        }

        if (intent?.action == "ACTION_INIT_LOUPE") {
            val code = intent.getIntExtra("RESULT_CODE", Activity.RESULT_CANCELED)
            val data: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra("DATA", Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra("DATA")
            }
            if (code == Activity.RESULT_OK && data != null) {
                try {
                    promoteToForegroundMediaProjection()
                    setupMediaProjection(code, data)
                    isLoupeActive = true
                    loupeView.visibility = View.VISIBLE
                    loupeBtnRef?.setBackgroundColor(Color.parseColor("#AA0088CC"))
                    mainHandler.postDelayed({ updateLoupe() }, 300)
                } catch (e: Exception) {
                    Toast.makeText(this, "Erreur loupe : ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun initAllViews() {
        if (viewsAdded) return
        setupCanvas()
        setupLoupe()
        setupCoordsBox()
        setupSidePanel()
        loadInitialLines()
        viewsAdded = true
    }

    private fun promoteToForegroundMediaProjection() {
        val channelId = "pixel_ruler_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "PixelRuler Loupe", NotificationManager.IMPORTANCE_LOW)
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("PixelRuler Loupe x20")
                .setContentText("Loupe ronde active")
                .setSmallIcon(android.R.drawable.ic_menu_crop)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("PixelRuler Loupe x20")
                .setSmallIcon(android.R.drawable.ic_menu_crop)
                .build()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notif)
        }
    }

    private fun setupMediaProjection(code: Int, data: Intent) {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(code, data)

        mediaProjection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                mainHandler.post {
                    isLoupeActive = false
                    if (viewsAdded) {
                        loupeView.visibility = View.GONE
                        loupeBtnRef?.setBackgroundColor(Color.parseColor("#44FFFFFF"))
                    }
                }
            }
        }, mainHandler)

        imageReader = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        imageReader?.setOnImageAvailableListener({ reader ->
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes.first()
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * screenW
                val bmp = Bitmap.createBitmap(screenW + rowPadding / pixelStride, screenH, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(buffer)
                screenBitmap = Bitmap.createBitmap(bmp, 0, 0, screenW, screenH)
                if (isLoupeActive) {
                    mainHandler.post { refreshLoupeDisplay() }
                }
            } finally {
                image.close()
            }
        }, mainHandler)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PixelRulerCapture", screenW, screenH, screenDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, mainHandler
        )
    }

    private fun loadInitialLines() {
        val defaultCenterX = (screenW * 0.42f).toInt()
        val defaultCenterY = (screenH * 0.45f).toInt()
        val defX = prefs.getInt("default_x", defaultCenterX).toFloat().coerceIn(80f, (screenW - 80).toFloat())
        val defY = prefs.getInt("default_y", defaultCenterY).toFloat().coerceIn(150f, (screenH - 150).toFloat())
        val colorH = prefs.getInt("color_h", Color.RED)
val colorV = prefs.getInt("color_v", Color.CYAN)
canvasView.addLine(RulerLine(true, defY, colorH))
canvasView.addLine(RulerLine(false, defX, colorV))
canvasView.selectedLine = canvasView.getAllLines().firstOrNull()
canvasView.invalidate()
updateCoordsDisplay()
}
private fun setupCanvas() {
canvasView = RulerCanvasView(this) {
updateCoordsDisplay()
if (isLoupeActive) updateLoupe()
}
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
private fun toggleTouchPassThrough(btn: Button) {
isTouchEnabled = !isTouchEnabled
if (isTouchEnabled) {
canvasParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
btn.text = "Tactile: ON"
btn.setBackgroundColor(Color.parseColor("#AA009944"))
} else {
canvasParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
btn.text = "Tactile: OFF"
btn.setBackgroundColor(Color.parseColor("#AA666666"))
}
wm.updateViewLayout(canvasView, canvasParams)
canvasView.showArrows = isTouchEnabled
canvasView.invalidate()
}
private fun setupLoupe() {
loupeView = RoundLoupeView(this) { dx, dy ->
loupeParams.x += dx
loupeParams.y += dy
wm.updateViewLayout(loupeView, loupeParams)
refreshLoupeDisplay()
}
val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
// Fenêtre 420x420 : cercle central de rayon 140px (centre à 210, 210) + orbite libre pour le point autour
loupeParams = WindowManager.LayoutParams(
420, 420, type,
WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
PixelFormat.TRANSLUCENT
).apply {
gravity = Gravity.TOP or Gravity.START
x = (screenW / 2) - 210
y = (screenH / 2) - 210
}
loupeView.visibility = View.GONE
wm.addView(loupeView, loupeParams)
}
private fun updateLoupe() {
if (!isLoupeActive) return
refreshLoupeDisplay()
}
private fun refreshLoupeDisplay() {
val allLines = canvasView.getAllLines()
val centerX = (loupeParams.x + 210).toFloat()
val centerY = (loupeParams.y + 210).toFloat()
loupeView.updateData(screenBitmap, centerX, centerY, allLines)
}
private fun setupSidePanel() {
panelLayout = LinearLayout(this).apply {
orientation = LinearLayout.VERTICAL
setBackgroundColor(Color.parseColor("#DD1A1A1A"))
setPadding(14, 18, 14, 18)
}
lateinit var touchBtn: Button
val buttons = listOf(
"Off" to {
cleanupAndStop()
},
"Tactile: ON" to { toggleTouchPassThrough(touchBtn) },
"+ H" to {
val c = prefs.getInt("color_h", Color.RED)
val y = (screenH * 0.35f) + (canvasView.lineCount() * 40f)
val newLine = RulerLine(true, y.coerceAtMost(screenH - 120f), c)
canvasView.addLine(newLine)
canvasView.selectedLine = newLine
canvasView.invalidate()
updateCoordsDisplay()
if (isLoupeActive) updateLoupe()
},
"+ V" to {
val c = prefs.getInt("color_v", Color.CYAN)
val x = (screenW * 0.35f) + (canvasView.lineCount() * 40f)
val newLine = RulerLine(false, x.coerceAtMost(screenW - 120f), c)
canvasView.addLine(newLine)
canvasView.selectedLine = newLine
canvasView.invalidate()
updateCoordsDisplay()
if (isLoupeActive) updateLoupe()
},
"-" to {
canvasView.removeSelectedOrLast()
canvasView.invalidate()
updateCoordsDisplay()
if (isLoupeActive) updateLoupe()
},
"Loupe" to {
if (mediaProjection == null) {
val permIntent = Intent(this, LoupePermissionActivity::class.java).apply {
addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
startActivity(permIntent)
} else {
isLoupeActive = !isLoupeActive
loupeView.visibility = if (isLoupeActive) View.VISIBLE else View.GONE
loupeBtnRef?.setBackgroundColor(if (isLoupeActive) Color.parseColor("#AA0088CC") else Color.parseColor("#44FFFFFF"))
if (isLoupeActive) updateLoupe()
}
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
textSize = 12f
isAllCaps = false
setTypeface(null, Typeface.BOLD)
setTextColor(Color.WHITE)
setPadding(10, 0, 10, 0)
gravity = Gravity.CENTER
setBackgroundColor(
when (label) {
"Off" -> Color.parseColor("#BBCC0000")
"Tactile: ON" -> Color.parseColor("#AA009944")
else -> Color.parseColor("#44FFFFFF")
}
)
val lp = LinearLayout.LayoutParams(250, 96).apply { setMargins(0, 6, 0, 6) }
layoutParams = lp
setOnClickListener { action() }
}
if (label == "Loupe") loupeBtnRef = btn
if (label == "Tactile: ON") touchBtn = btn
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
setPadding(18, 14, 18, 14)
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
coordsParams.x = if (isPanelRight) 20 else 300
coordsParams.y = 100
wm.updateViewLayout(coordsBox, coordsParams)
}
private fun updateCoordsDisplay() {
coordsBox.removeAllViews()
val header = TextView(this).apply {
text = if (areCoordsFloating) "📌 Coords (Ranger)" else "📐 Coords (Glisser)"
textSize = 12f
setTextColor(Color.LTGRAY)
setOnClickListener { dockCoordsBox() }
}
coordsBox.addView(header)
val allLines = canvasView.getAllLines()
val hLines = allLines.filter { it.isHorizontal }
val vLines = allLines.filter { !it.isHorizontal }
if (hLines.isEmpty() || vLines.isEmpty()) {
val info = TextView(this).apply {
text = "Ajoutez 1 H et 1 V"
textSize = 12f
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
textSize = 14f
setTypeface(null, Typeface.BOLD)
setTextColor(v.color)
}
val tvY = TextView(this).apply {
text = "Y:${h.pos.toInt()}"
textSize = 14f
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
setText(prefs.getInt("default_x", (screenW * 0.42f).toInt()).toString())
}
val inputY = EditText(this).apply {
hint = "Valeur Y de base (px)"
inputType = InputType.TYPE_CLASS_NUMBER
setText(prefs.getInt("default_y", (screenH * 0.45f).toInt()).toString())
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
layout.addView(TextView(this).apply { text = "Coordonnées de base (X / Y) :" })
layout.addView(inputX)
layout.addView(inputY)
layout.addView(lblH)
layout.addView(spinH)
layout.addView(lblV)
layout.addView(spinV)
val dialog = AlertDialog.Builder(this)
.setTitle("Paramètres de précision")
.setView(layout)
.setPositiveButton("Enregistrer") { _, _ ->
val newX = inputX.text.toString().toIntOrNull() ?: (screenW * 0.42f).toInt()
val newY = inputY.text.toString().toIntOrNull() ?: (screenH * 0.45f).toInt()
val chosenH = colors[colorNames[spinH.selectedItemPosition]] ?: Color.RED
val chosenV = colors[colorNames[spinV.selectedItemPosition]] ?: Color.CYAN
prefs.edit()
.putInt("default_x", newX)
.putInt("default_y", newY)
.putInt("color_h", chosenH)
.putInt("color_v", chosenV)
.apply()
for (line in canvasView.getAllLines()) {
line.color = if (line.isHorizontal) chosenH else chosenV
}
canvasView.selectedLine?.let { line ->
line.pos = if (line.isHorizontal) newY.toFloat() else newX.toFloat()
}
canvasView.invalidate()
updateCoordsDisplay()
if (isLoupeActive) updateLoupe()
Toast.makeText(this, "Paramètres sauvegardés", Toast.LENGTH_SHORT).show()
}
.setNegativeButton("Annuler", null)
.create()
val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
dialog.window?.setType(type)
dialog.show()
}
private fun cleanupAndStop() {
try {
virtualDisplay?.release()
virtualDisplay = null
imageReader?.close()
imageReader = null
mediaProjection?.stop()
mediaProjection = null
} catch (e: Exception) {}
if (viewsAdded) {
try { wm.removeViewImmediate(canvasView) } catch (e: Exception) {}
try { wm.removeViewImmediate(panelLayout) } catch (e: Exception) {}
try { wm.removeViewImmediate(coordsBox) } catch (e: Exception) {}
try { wm.removeViewImmediate(loupeView) } catch (e: Exception) {}
viewsAdded = false
}
try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (e: Exception) {}
stopSelf()
}
override fun onDestroy() {
cleanupAndStop()
super.onDestroy()
}
}
class RoundLoupeView(context: Context, val onDrag: (Int, Int) -> Unit) : View(context) {
private var sourceBitmap: Bitmap? = null
private var focusX = 0f
private var focusY = 0f
private var lines: Array = emptyArray()
private val zoomFactor = 20f
private val radius = 140f
private val cx = 210f
private val cy = 210f
private val orbitRadius = 168f
private var handleAngle = Math.toRadians(45.0).toFloat()
private val handleRadius = 34f
private var dragMode = 0 // 0: aucun, 1: déplacer la loupe, 2: faire tourner le point autour de la loupe
private var lastRawX = 0f
private var lastRawY = 0f
private val clipPath = Path().apply {
addCircle(cx, cy, radius, Path.Direction.CW)
}
private val borderPaint = Paint().apply {
color = Color.WHITE
style = Paint.Style.STROKE
strokeWidth = 5f
isAntiAlias = true
}
private val bgPaint = Paint().apply {
color = Color.parseColor("#EE111111")
isAntiAlias = true
}
private val pixelPaint = Paint().apply {
isFilterBitmap = false
isAntiAlias = false
}
private val linePaint = Paint().apply { strokeWidth = 2f }
private val crossPaint = Paint().apply {
color = Color.parseColor("#88FFFFFF")
strokeWidth = 1f
}
private val handlePaint = Paint().apply {
color = Color.parseColor("#EE0088CC")
style = Paint.Style.FILL
isAntiAlias = true
}
private val handleIconPaint = Paint().apply {
color = Color.WHITE
textSize = 28f
textAlign = Paint.Align.CENTER
isFakeBoldText = true
isAntiAlias = true
}
private val pillPaint = Paint().apply {
color = Color.parseColor("#CC000000")
isAntiAlias = true
}
private val textPaint = Paint().apply {
color = Color.WHITE
textSize = 20f
textAlign = Paint.Align.CENTER
isFakeBoldText = true
isAntiAlias = true
}
fun updateData(bmp: Bitmap?, fx: Float, fy: Float, allLines: Array) {
sourceBitmap = bmp
focusX = fx
focusY = fy
lines = allLines
invalidate()
}
override fun onDraw(canvas: Canvas) {
super.onDraw(canvas)
// 1. Contenu de la loupe découpé en cercle au centre (210, 210)
canvas.save()
canvas.clipPath(clipPath)
canvas.drawCircle(cx, cy, radius, bgPaint)
sourceBitmap?.let { bmp ->
val diameter = radius * 2f
val halfSpan = (diameter / zoomFactor) / 2f
val srcLeft = (focusX - halfSpan).toInt().coerceIn(0, bmp.width - 1)
val srcTop = (focusY - halfSpan).toInt().coerceIn(0, bmp.height - 1)
val srcRight = (focusX + halfSpan).toInt().coerceIn(srcLeft + 1, bmp.width)
val srcBottom = (focusY + halfSpan).toInt().coerceIn(srcTop + 1, bmp.height)
val srcRect = Rect(srcLeft, srcTop, srcRight, srcBottom)
val dstRect = Rect((cx - radius).toInt(), (cy - radius).toInt(), (cx + radius).toInt(), (cy + radius).toInt())
canvas.drawBitmap(bmp, srcRect, dstRect, pixelPaint)
}
// Lignes de mesure dans le cercle
for (line in lines) {
linePaint.color = line.color
if (line.isHorizontal) {
val dy = (line.pos - focusY) * zoomFactor + cy
if (dy in (cy - radius)..(cy + radius)) canvas.drawLine(cx - radius, dy, cx + radius, dy, linePaint)
} else {
val dx = (line.pos - focusX) * zoomFactor + cx
if (dx in (cx - radius)..(cx + radius)) canvas.drawLine(dx, cy - radius, dx, cy + radius, linePaint)
}
}
// Viseur central
canvas.drawLine(cx - 20f, cy, cx + 20f, cy, crossPaint)
canvas.drawLine(cx, cy - 20f, cx, cy + 20f, crossPaint)
// Coordonnées en bas du cercle
canvas.drawRect(cx - 105f, cy + radius - 46f, cx + 105f, cy + radius, pillPaint)
canvas.drawText("X:${focusX.toInt()} Y:${focusY.toInt()}", cx, cy + radius - 22f, textPaint)
canvas.restore()
// 2. Bordure blanche du cercle
canvas.drawCircle(cx, cy, radius - 2f, borderPaint)
// 3. Point poignée positionnable librement autour du cercle
val hx = cx + orbitRadius * cos(handleAngle)
val hy = cy + orbitRadius * sin(handleAngle)
canvas.drawCircle(hx, hy, handleRadius, handlePaint)
canvas.drawCircle(hx, hy, handleRadius - 2f, borderPaint)
canvas.drawText("✥", hx, hy + 10f, handleIconPaint)
}
override fun onTouchEvent(event: MotionEvent): Boolean {
val x = event.x
val y = event.y
val hx = cx + orbitRadius * cos(handleAngle)
val hy = cy + orbitRadius * sin(handleAngle)
when (event.action) {
MotionEvent.ACTION_DOWN -> {
val distHandle = hypot(x - hx, y - hy)
val distCircle = hypot(x - cx, y - cy)
if (distHandle <= handleRadius + 22f) {
dragMode = 1
lastRawX = event.rawX
lastRawY = event.rawY
return true
} else if (distCircle <= radius) {
dragMode = 2
lastRawX = event.rawX
lastRawY = event.rawY
return true
}
return false
}
MotionEvent.ACTION_MOVE -> {
if (dragMode == 1) {
// Déplace la loupe entière via le point poignée
val dx = (event.rawX - lastRawX).toInt()
val dy = (event.rawY - lastRawY).toInt()
lastRawX = event.rawX
lastRawY = event.rawY
onDrag(dx, dy)
return true
} else if (dragMode == 2) {
// Fait tourner le point poignée autour de la loupe si on glisse sur le bord
val dCenter = hypot(x - cx, y - cy)
if (dCenter > radius * 0.55f) {
handleAngle = atan2(y - cy, x - cx)
invalidate()
} else {
val dx = (event.rawX - lastRawX).toInt()
val dy = (event.rawY - lastRawY).toInt()
lastRawX = event.rawX
lastRawY = event.rawY
onDrag(dx, dy)
}
return true
}
}
MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
dragMode = 0
}
}
return false
}
}
class RulerCanvasView(context: Context, val onUpdate: () -> Unit) : View(context) {
private val lines = ArrayList()
var selectedLine: RulerLine? = null
var showArrows = true
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
if (!showArrows) return
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
selectedLine?.let { line ->
if (line.isHorizontal) {
val cx = width / 2f
val yUp = (line.pos - 75f).coerceAtLeast(50f)
val yDown = (line.pos + 75f).coerceAtMost(height - 50f)
if (abs(x - cx) < 55f && abs(y - yUp) < 55f) {
line.pos = (line.pos - 1f).coerceAtLeast(0f)
invalidate(); onUpdate(); return true
}
if (abs(x - cx) < 55f && abs(y - yDown) < 55f) {
line.pos = (line.pos + 1f).coerceAtMost(height.toFloat())
invalidate(); onUpdate(); return true
}
} else {
val cy = height / 2f
val xLeft = (line.pos - 75f).coerceAtLeast(50f)
val xRight = (line.pos + 75f).coerceAtMost(width - 50f)
if (abs(x - xLeft) < 55f && abs(y - cy) < 55f) {
line.pos = (line.pos - 1f).coerceAtLeast(0f)
invalidate(); onUpdate(); return true
}
if (abs(x - xRight) < 55f && abs(y - cy) < 55f) {
line.pos = (line.pos + 1f).coerceAtMost(width.toFloat())
invalidate(); onUpdate(); return true
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
onUpdate()
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
