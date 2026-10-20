package com.walkman.remote

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Весь экран пульта. Рисуется вручную по координатам из Design.kt.
 */
@SuppressLint("ViewConstructor")
class WalkmanRemoteView(context: Context) : View(context) {

    private enum class Mode { NONE, DISC, VOLUME, PREV, NEXT, COLOR }

    // ------------------------------------------------------------------
    // Настройки и шрифты
    // ------------------------------------------------------------------
    private val prefs = context.getSharedPreferences("walkman_remote", Context.MODE_PRIVATE)
    private var accentColor: Int = prefs.getInt("accent_color", Design.DEFAULT_ACCENT)

    private val monoFace: Typeface = loadFont(R.font.roboto_mono, Typeface.MONOSPACE)
    private val flexFace: Typeface = loadFont(R.font.roboto_flex, Typeface.SANS_SERIF)

    private fun loadFont(resId: Int, fallback: Typeface): Typeface {
        return try {
            resources.getFont(resId)
        } catch (e: Exception) {
            fallback
        }
    }

    // ------------------------------------------------------------------
    // Кисти
    // ------------------------------------------------------------------
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = Design.LINE
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = accentColor
    }
    private val monoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = monoFace
        textSize = Design.MONO_SIZE
    }
    private val flexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = flexFace
        textSize = Design.FLEX_SIZE
    }
    private val buttonLetterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = monoFace
        textSize = Design.C_SIZE
    }
    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // ------------------------------------------------------------------
    // Иконки стрелок (из rewind.svg и forward.svg)
    // ------------------------------------------------------------------
    private val rewindPath = Path().apply {
        moveTo(41.6494f, 81.1774f)
        lineTo(1.41421f, 40.9422f)
        lineTo(41.6494f, 0.707031f)
    }
    private val forwardPath = Path().apply {
        moveTo(0.707038f, 0.70732f)
        lineTo(40.9422f, 40.9425f)
        lineTo(0.707031f, 81.1777f)
    }

    // ------------------------------------------------------------------
    // Геометрия громкости
    // ------------------------------------------------------------------
    private val volOuterRect = RectF(
        Design.VOL_CX - Design.VOL_R, Design.VOL_CY - Design.VOL_R,
        Design.VOL_CX + Design.VOL_R, Design.VOL_CY + Design.VOL_R
    )
    private val volInnerRect = RectF(
        Design.VOL_CX - Design.VOL_INNER_R, Design.VOL_CY - Design.VOL_INNER_R,
        Design.VOL_CX + Design.VOL_INNER_R, Design.VOL_CY + Design.VOL_INNER_R
    )
    private val volFillPath = Path()

    // ------------------------------------------------------------------
    // Состояние
    // ------------------------------------------------------------------
    private var batteryPercent = 0
    private var memPercent = 0
    private var sdPercent = 0
    private var sdPresent = false

    private var songTitle = "No track playing"
    private var artistName = "Walkman A300"
    private var titleShown = songTitle
    private var artistShown = artistName
    private var trackText = "0/0"

    private var trackDurationMs = 0L
    private var currentPositionMs = 0L
    private var isPlaying = false
    private var coverBitmap: Bitmap? = null

    private var discAngle = 0f
    private var volumePercent = 0

    private var elapsedText = "+ 00:00"
    private var remainText = "- 00:00"
    private var shownSecond = -1L

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------------
    // Состояние касаний
    // ------------------------------------------------------------------
    private var mode = Mode.NONE
    private var pressedCell = -1            // 0 = назад, 2 = вперёд
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // диск
    private var downX = 0f
    private var downY = 0f
    private var startedInCenter = false
    private var scrubbing = false
    private var lastAngle = 0f

    // стрелки
    private var longPressed = false
    private var seekRunnable: Runnable? = null

    // громкость
    private var volStartProgress = 0f
    private var volStartPercent = 0
    private var lastSentVolume = -1
    private var toastShownForThisDrag = false

    // ------------------------------------------------------------------
    // Анимация и фоновые обновления
    // ------------------------------------------------------------------
    private var lastFrameTime = 0L
    private var frameLoopRunning = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = now - lastFrameTime
            lastFrameTime = now
            if (isPlaying && !scrubbing) {
                discAngle = (discAngle + dt * Design.DISC_DEG_PER_MS) % 360f
                currentPositionMs = (currentPositionMs + dt).coerceIn(0L, maxPosition())
            }
            invalidate()
            if (isPlaying) {
                postOnAnimation(this)
            } else {
                frameLoopRunning = false
            }
        }
    }

    private fun ensureFrameLoop() {
        if (!frameLoopRunning && isAttachedToWindow) {
            frameLoopRunning = true
            lastFrameTime = SystemClock.uptimeMillis()
            postOnAnimation(frameRunnable)
        }
    }

    private var housekeepingTick = 0
    private val housekeeping = object : Runnable {
        override fun run() {
            if (mode != Mode.VOLUME) syncVolumeFromSystem()
            housekeepingTick++
            if (housekeepingTick % 100 == 0) {
                updateStorageStats()
                invalidate()
            }
            postDelayed(this, 300)
        }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            readBattery(intent)
            invalidate()
        }
    }

    private fun readBattery(intent: Intent?) {
        intent ?: return
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) batteryPercent = (level * 100) / scale
    }

    // ------------------------------------------------------------------
    // Подключение / отключение
    // ------------------------------------------------------------------
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        try {
            readBattery(context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
        } catch (e: Exception) {
        }

        updateStorageStats()
        syncVolumeFromSystem()

        WalkmanNotificationListener.onMetadataChanged = { meta -> applyMetadata(meta) }
        WalkmanNotificationListener.onStateChanged = { state -> applyState(state) }

        // Если сессия уже найдена, сразу подтянем текущий трек
        WalkmanNotificationListener.activeController?.let {
            applyMetadata(it.metadata)
            applyState(it.playbackState)
        }

        postDelayed(housekeeping, 300)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
        }
        WalkmanNotificationListener.onMetadataChanged = null
        WalkmanNotificationListener.onStateChanged = null
        removeCallbacks(housekeeping)
        removeCallbacks(frameRunnable)
        frameLoopRunning = false
        stopSeekRepeat()
    }

    // ------------------------------------------------------------------
    // Данные: память, громкость, трек
    // ------------------------------------------------------------------
    private fun updateStorageStats() {
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val total = stat.totalBytes
            val free = stat.availableBytes
            if (total > 0) memPercent = (((total - free) * 100) / total).toInt()

            sdPresent = false
            for (dir in context.getExternalFilesDirs(null)) {
                if (dir != null && Environment.isExternalStorageRemovable(dir)) {
                    val sd = StatFs(dir.path)
                    if (sd.totalBytes > 0) {
                        sdPercent = (((sd.totalBytes - sd.availableBytes) * 100) / sd.totalBytes).toInt()
                        sdPresent = true
                        break
                    }
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun syncVolumeFromSystem() {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val pct = if (max > 0) (cur * 100f / max).roundToInt() else 0
        if (pct != volumePercent) {
            volumePercent = pct
            invalidate()
        }
    }

    private fun applyMetadata(meta: MediaMetadata?) {
        if (meta == null) return
        songTitle = meta.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Unknown Title"
        artistName = meta.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: "Unknown Artist"
        trackDurationMs = meta.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val raw = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        coverBitmap = raw?.let { makeCover(it) }

        titleShown = fit(songTitle, flexPaint, Design.TEXT_MAX_W)
        artistShown = fit(artistName, flexPaint, Design.TEXT_MAX_W)

        refreshTrackText(meta)
        shownSecond = -1L
        invalidate()
    }

    private fun applyState(state: PlaybackState?) {
        if (state == null) return
        isPlaying = state.state == PlaybackState.STATE_PLAYING

        var pos = state.position
        if (pos < 0) pos = 0
        if (isPlaying && state.lastPositionUpdateTime > 0) {
            val age = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            if (age > 0) pos += (age * state.playbackSpeed).toLong()
        }
        currentPositionMs = pos.coerceIn(0L, maxPosition())

        refreshTrackText(WalkmanNotificationListener.activeController?.metadata)
        shownSecond = -1L
        invalidate()
        if (isPlaying) ensureFrameLoop()
    }

    private fun refreshTrackText(meta: MediaMetadata?) {
        try {
            val ctrl = WalkmanNotificationListener.activeController
            val queue = ctrl?.queue
            val activeId = ctrl?.playbackState?.activeQueueItemId ?: -1L
            if (queue != null && queue.isNotEmpty() && activeId != -1L) {
                val idx = queue.indexOfFirst { it.queueId == activeId }
                if (idx >= 0) {
                    trackText = "${idx + 1}/${queue.size}"
                    return
                }
            }
            if (meta != null) {
                val cur = meta.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER)
                val total = meta.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS)
                if (cur > 0 && total > 0) trackText = "$cur/$total"
            }
        } catch (e: Exception) {
        }
    }

    private fun maxPosition(): Long = if (trackDurationMs > 0) trackDurationMs else Long.MAX_VALUE

    // Из обложки делаем круг нужного размера (с обрезкой по центру)
    private fun makeCover(bmp: Bitmap): Bitmap {
        val size = ((Design.COVER_R + 1f) * 2f).toInt()
        val side = min(bmp.width, bmp.height)
        val left = (bmp.width - side) / 2
        val top = (bmp.height - side) / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawCircle(size / 2f, size / 2f, size / 2f, p)
        p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        c.drawBitmap(bmp, Rect(left, top, left + side, top + side), Rect(0, 0, size, size), p)
        return out
    }

    // Обрезка длинного текста с многоточием
    private fun fit(text: String, paint: Paint, maxW: Float): String {
        if (paint.measureText(text) <= maxW) return text
        val dots = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText(dots) > maxW) end--
        return text.substring(0, end).trimEnd() + dots
    }

    private fun updateTimeStrings() {
        val sec = currentPositionMs / 1000
        if (sec == shownSecond) return
        shownSecond = sec
        val remain = if (trackDurationMs > 0) ((trackDurationMs - currentPositionMs).coerceAtLeast(0L)) / 1000 else 0L
        elapsedText = String.format(Locale.US, "+ %02d:%02d", sec / 60, sec % 60)
        remainText = String.format(Locale.US, "- %02d:%02d", remain / 60, remain % 60)
    }

    // ------------------------------------------------------------------
    // Рисование
    // ------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (width == 0 || height == 0) return

        // Если вдруг экран не 720x1280, масштабируем дизайн
        canvas.save()
        canvas.scale(width / Design.W, height / Design.H)

        accentPaint.color = accentColor
        updateTimeStrings()

        drawTopRow(canvas)
        drawDisc(canvas)
        drawTitleRow(canvas)
        drawVolume(canvas)

        canvas.restore()
    }

    private fun drawRight(canvas: Canvas, text: String, rightX: Float, baseline: Float, paint: Paint) {
        canvas.drawText(text, rightX - paint.measureText(text), baseline, paint)
    }

    private fun drawCentered(canvas: Canvas, text: String, centerX: Float, baseline: Float, paint: Paint) {
        canvas.drawText(text, centerX - paint.measureText(text) / 2f, baseline, paint)
    }

    private fun drawStatCell(canvas: Canvas, x0: Float, label: String, percent: Int, present: Boolean) {
        if (present) {
            val w = Design.CELL_W * percent.coerceIn(0, 100) / 100f
            if (w > 0f) canvas.drawRect(x0, 0f, x0 + w, Design.TOP_H, accentPaint)
            canvas.drawText(label, x0 + Design.PAD, Design.TOP_BASE, monoPaint)
            drawRight(canvas, "$percent%", x0 + Design.CELL_W - Design.PAD, Design.TOP_BASE, monoPaint)
        } else {
            // SD-карты нет: белый крестик
            val cx = x0 + Design.SD_X_CENTER
            val cy = Design.SD_X_CENTER_Y
            val d = Design.SD_X_HALF
            canvas.drawLine(cx - d, cy - d, cx + d, cy + d, linePaint)
            canvas.drawLine(cx - d, cy + d, cx + d, cy - d, linePaint)
        }
    }

    private fun drawTopRow(canvas: Canvas) {
        drawStatCell(canvas, 0f, "B", batteryPercent, true)
        drawStatCell(canvas, Design.CELL_W, "M", memPercent, true)
        drawStatCell(canvas, Design.CELL_W * 2, "SD", sdPercent, sdPresent)

        canvas.drawLine(0f, Design.TOP_H, Design.W, Design.TOP_H, linePaint)
        canvas.drawLine(Design.CELL_W, 0f, Design.CELL_W, Design.TOP_H, linePaint)
        canvas.drawLine(Design.CELL_W * 2, 0f, Design.CELL_W * 2, Design.TOP_H, linePaint)
    }

    private fun drawDisc(canvas: Canvas) {
        val cx = Design.DISC_CX
        val cy = Design.DISC_CY
        val r = Design.DISC_R

        // Таймеры и номер трека
        canvas.drawText(elapsedText, Design.TIMER_PAD, Design.TIMER_BASE, monoPaint)
        drawRight(canvas, remainText, Design.W - Design.TIMER_PAD, Design.TIMER_BASE, monoPaint)
        canvas.drawText(trackText, Design.TIMER_PAD, Design.COUNTER_BASE, monoPaint)

        // Внешний круг
        canvas.drawCircle(cx, cy, r, linePaint)

        // Вращающаяся часть: две линии и обложка
        canvas.save()
        canvas.rotate(discAngle, cx, cy)
        canvas.drawLine(cx - r, cy, cx + r, cy, linePaint)
        canvas.drawLine(cx, cy - r, cx, cy + r, linePaint)
        val cover = coverBitmap
        if (cover != null) {
            canvas.drawBitmap(cover, cx - cover.width / 2f, cy - cover.height / 2f, coverPaint)
        }
        canvas.restore()

        // Внутренний круг и красная точка
        canvas.drawCircle(cx, cy, Design.COVER_R, linePaint)
        if (cover == null) {
            canvas.drawCircle(cx, cy, Design.DOT_R, accentPaint)
        }

        // Нижняя граница области диска
        canvas.drawLine(0f, Design.DISC_BOTTOM, Design.W, Design.DISC_BOTTOM, linePaint)

        // Круглая кнопка "C"
        canvas.drawCircle(Design.C_CX, Design.C_CY, Design.C_R, accentPaint)
        drawCentered(canvas, "C", Design.C_CX, Design.C_CY + Design.C_SIZE * 0.36f, buttonLetterPaint)
    }

    private fun drawTitleRow(canvas: Canvas) {
        val top = Design.DISC_BOTTOM
        val bottom = Design.TITLE_BOTTOM

        // Подсветка нажатой стрелки
        if (pressedCell == 0) canvas.drawRect(0f, top, Design.ARROW_L_END, bottom, accentPaint)
        if (pressedCell == 2) canvas.drawRect(Design.ARROW_R_START, top, Design.W, bottom, accentPaint)

        // Линии
        canvas.drawLine(0f, bottom, Design.W, bottom, linePaint)
        canvas.drawLine(Design.ARROW_L_END, top, Design.ARROW_L_END, bottom, linePaint)
        canvas.drawLine(Design.ARROW_R_START, top, Design.ARROW_R_START, bottom, linePaint)

        // Стрелки
        canvas.save()
        canvas.translate(Design.ARROW_L_X, Design.ARROW_Y)
        canvas.drawPath(rewindPath, linePaint)
        canvas.restore()

        canvas.save()
        canvas.translate(Design.ARROW_R_X, Design.ARROW_Y)
        canvas.drawPath(forwardPath, linePaint)
        canvas.restore()

        // Названия
        canvas.drawText(titleShown, Design.TEXT_X, Design.TITLE_BASE, flexPaint)
        canvas.drawText(artistShown, Design.TEXT_X, Design.ARTIST_BASE, flexPaint)
    }

    private fun drawVolume(canvas: Canvas) {
        val sweep = 180f * volumePercent.coerceIn(0, 100) / 100f
        if (sweep > 0f) {
            volFillPath.reset()
            volFillPath.arcTo(volOuterRect, 180f, sweep, true)
            volFillPath.arcTo(volInnerRect, 180f + sweep, -sweep, false)
            volFillPath.close()
            canvas.drawPath(volFillPath, accentPaint)
        }
        canvas.drawArc(volOuterRect, 180f, 180f, false, linePaint)
        canvas.drawArc(volInnerRect, 180f, 180f, false, linePaint)
        drawCentered(canvas, "$volumePercent%", Design.VOL_CX, Design.VOL_TEXT_BASE, monoPaint)
    }

    // ------------------------------------------------------------------
    // Касания
    // ------------------------------------------------------------------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return true
        val x = event.x * Design.W / width
        val y = event.y * Design.H / height

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(x, y)
            MotionEvent.ACTION_MOVE -> onMove(x, y)
            MotionEvent.ACTION_UP -> onUp(x, y, false)
            MotionEvent.ACTION_CANCEL -> onUp(x, y, true)
        }
        return true
    }

    private fun angleAt(x: Float, y: Float): Float {
        val a = Math.toDegrees(atan2((y - Design.DISC_CY).toDouble(), (x - Design.DISC_CX).toDouble())).toFloat()
        return (a + 360f) % 360f
    }

    // 0 = левый край полукруга, 1 = правый край
    private fun volumeProgress(x: Float, y: Float): Float {
        val a = Math.toDegrees(
            atan2((Design.VOL_CY - y).toDouble(), (x - Design.VOL_CX).toDouble())
        ).coerceIn(0.0, 180.0)
        return (1.0 - a / 180.0).toFloat()
    }

    private fun onDown(x: Float, y: Float) {
        mode = Mode.NONE
        scrubbing = false
        longPressed = false

        // Круглая кнопка "C"
        if (hypot(x - Design.C_CX, y - Design.C_CY) <= Design.C_R + 4f) {
            mode = Mode.COLOR
            return
        }

        // Диск
        val dDisc = hypot(x - Design.DISC_CX, y - Design.DISC_CY)
        if (y in Design.TOP_H..Design.DISC_BOTTOM && dDisc <= Design.DISC_R + 1f) {
            mode = Mode.DISC
            downX = x
            downY = y
            startedInCenter = dDisc <= Design.COVER_R
            return
        }

        // Строка со стрелками
        if (y > Design.DISC_BOTTOM && y <= Design.TITLE_BOTTOM) {
            if (x < Design.ARROW_L_END) {
                mode = Mode.PREV
                pressedCell = 0
                startLongPress(-1)
                invalidate()
            } else if (x >= Design.ARROW_R_START) {
                mode = Mode.NEXT
                pressedCell = 2
                startLongPress(1)
                invalidate()
            }
            return
        }

        // Громкость
        if (y > Design.TITLE_BOTTOM && hypot(x - Design.VOL_CX, y - Design.VOL_CY) <= Design.VOL_R + 1f) {
            mode = Mode.VOLUME
            volStartProgress = volumeProgress(x, y)
            volStartPercent = volumePercent
            lastSentVolume = -1
            toastShownForThisDrag = false
        }
    }

    private fun onMove(x: Float, y: Float) {
        when (mode) {
            Mode.DISC -> {
                if (!scrubbing) {
                    if (hypot(x - downX, y - downY) > touchSlop) {
                        scrubbing = true
                        lastAngle = angleAt(x, y)
                    }
                } else {
                    val cur = angleAt(x, y)
                    var delta = cur - lastAngle
                    if (delta > 180f) delta -= 360f
                    if (delta < -180f) delta += 360f
                    lastAngle = cur

                    val deltaMs = (delta / 360f * Design.SCRUB_MS_PER_TURN).toLong()
                    currentPositionMs = (currentPositionMs + deltaMs).coerceIn(0L, maxPosition())
                    discAngle = (discAngle + delta + 360f) % 360f
                    invalidate()
                }
            }

            Mode.VOLUME -> {
                val p = volumeProgress(x, y)
                val target = (volStartPercent + (p - volStartProgress) * 100f)
                    .coerceIn(0f, 100f).roundToInt()
                if (target != volumePercent) {
                    volumePercent = target
                    setSystemVolume(target)
                    invalidate()
                }
            }

            else -> {
            }
        }
    }

    private fun onUp(x: Float, y: Float, cancelled: Boolean) {
        stopSeekRepeat()

        when (mode) {
            Mode.DISC -> {
                if (scrubbing) {
                    if (!cancelled) {
                        WalkmanNotificationListener.activeController?.transportControls?.seekTo(currentPositionMs)
                    }
                } else if (!cancelled && startedInCenter) {
                    togglePlayPause()
                }
                scrubbing = false
            }

            Mode.PREV, Mode.NEXT -> {
                if (!cancelled && !longPressed) {
                    val tc = WalkmanNotificationListener.activeController?.transportControls
                    if (mode == Mode.PREV) tc?.skipToPrevious() else tc?.skipToNext()
                }
            }

            Mode.VOLUME -> {
                finishVolumeDrag()
            }

            Mode.COLOR -> {
                if (!cancelled && hypot(x - Design.C_CX, y - Design.C_CY) <= Design.C_R + 12f) {
                    showColorPickerDialog()
                }
            }

            else -> {
            }
        }

        mode = Mode.NONE
        pressedCell = -1
        invalidate()
    }

    private fun togglePlayPause() {
        val ctrl = WalkmanNotificationListener.activeController
        if (ctrl == null) {
            Toast.makeText(context, "Плеер Sony не найден. Запустите музыку.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isPlaying) ctrl.transportControls.pause() else ctrl.transportControls.play()
    }

    // ------------------------------------------------------------------
    // Перемотка удержанием стрелки
    // ------------------------------------------------------------------
    private fun startLongPress(direction: Int) {
        val r = object : Runnable {
            override fun run() {
                longPressed = true
                currentPositionMs = (currentPositionMs + direction * Design.SEEK_STEP_MS)
                    .coerceIn(0L, maxPosition())
                WalkmanNotificationListener.activeController?.transportControls?.seekTo(currentPositionMs)
                shownSecond = -1L
                invalidate()
                mainHandler.postDelayed(this, Design.SEEK_REPEAT_MS)
            }
        }
        seekRunnable = r
        mainHandler.postDelayed(r, Design.LONG_PRESS_MS)
    }

    private fun stopSeekRepeat() {
        seekRunnable?.let { mainHandler.removeCallbacks(it) }
        seekRunnable = null
    }

    // ------------------------------------------------------------------
    // Громкость
    // ------------------------------------------------------------------
    private fun setSystemVolume(percent: Int) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = (percent * max / 100f).roundToInt()
        if (target == lastSentVolume) return
        lastSentVolume = target
        try {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: Exception) {
        }
    }

    // В конце движения проверяем, что система приняла громкость.
    // Если нет, пробуем подвинуть шагами, как это делают физические кнопки.
    private fun finishVolumeDrag() {
        if (lastSentVolume < 0) return
        val target = lastSentVolume
        try {
            var cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            var guard = 0
            while (cur != target && guard++ < 200) {
                audioManager.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (cur < target) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0
                )
                val next = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (next == cur) break
                cur = next
            }
            if (cur != target && !toastShownForThisDrag) {
                toastShownForThisDrag = true
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                Toast.makeText(
                    context,
                    "Громкость не применилась: нужно $target, в системе $cur из $max",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (e: Exception) {
        }
        syncVolumeFromSystem()
    }

    // ------------------------------------------------------------------
    // Выбор акцентного цвета
    // ------------------------------------------------------------------
    private fun showColorPickerDialog() {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
            setBackgroundColor(Color.parseColor("#1A1A1A"))
        }

        val rgb = intArrayOf(Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

        val preview = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 80)
            setBackgroundColor(accentColor)
        }
        layout.addView(preview)

        fun addChannelSlider(label: String, index: Int) {
            val text = TextView(context).apply {
                this.text = "$label: ${rgb[index]}"
                setTextColor(Color.WHITE)
                typeface = monoFace
                setPadding(0, 20, 0, 5)
            }
            layout.addView(text)

            val seek = SeekBar(context).apply {
                max = 255
                progress = rgb[index]
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                        rgb[index] = p
                        text.text = "$label: $p"
                        preview.setBackgroundColor(Color.rgb(rgb[0], rgb[1], rgb[2]))
                    }

                    override fun onStartTrackingTouch(s: SeekBar?) {}
                    override fun onStopTrackingTouch(s: SeekBar?) {}
                })
            }
            layout.addView(seek)
        }

        addChannelSlider("R", 0)
        addChannelSlider("G", 1)
        addChannelSlider("B", 2)

        AlertDialog.Builder(context)
            .setTitle("Accent Color")
            .setView(layout)
            .setPositiveButton("OK") { _, _ ->
                accentColor = Color.rgb(rgb[0], rgb[1], rgb[2])
                prefs.edit().putInt("accent_color", accentColor).apply()
                invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
