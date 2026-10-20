package com.walkman.remote

import android.annotation.SuppressLint
import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.StatFs
import android.os.SystemClock
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Весь экран пульта. Рисуется вручную по координатам из Design.kt.
 */
@SuppressLint("ViewConstructor")
class WalkmanRemoteView(context: Context) : View(context) {

    private enum class Mode { NONE, DISC, VOLUME, PREV, NEXT, COLOR, TITLE }

    private enum class SheetGesture { NONE, PENDING, SCROLL, DRAG_SHEET, SEEK, IGNORE }

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

    // кнопка "C"
    private var colorDownTime = 0L

    // стрелки
    private var longPressed = false
    private var seekRunnable: Runnable? = null

    // громкость
    private var volStartProgress = 0f
    private var volStartPercent = 0
    private var lastSentVolume = -1
    private var toastShownForThisDrag = false
    private var volDownTime = 0L
    private var volMoved = false

    // ------------------------------------------------------------------
    // Список треков: состояние
    // ------------------------------------------------------------------
    private var sheetVisible = false
    private var sheetY = Design.H            // 0 = экран открыт, H = спрятан внизу
    private var sheetAnimator: ValueAnimator? = null
    private var listScroll = 0f
    private var listFlingV = 0f
    private var flingLast = 0L
    private var listSeeking = false

    private var tracks: List<TrackItem> = emptyList()
    private var activeQueueId = -1L
    private var loadGeneration = 0
    private var formatKey: String? = null

    private var coverSquare: Bitmap? = null
    private var hdrArtistShown = ""
    private var hdrAlbumShown = ""
    private var hdrYear = ""
    private var hdrFormat = ""

    private var sg = SheetGesture.NONE
    private var sgDownX = 0f
    private var sgDownY = 0f
    private var sgLastY = 0f
    private var sgAtTop = true
    private var sgOnActive = false
    private var velocity: VelocityTracker? = null

    private val blackPaint = Paint().apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val smallMonoPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = monoFace
        textSize = Design.SMALL_SIZE
    }

    private val flingRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = (now - flingLast).coerceAtLeast(1L)
            flingLast = now
            val max = maxScroll()
            listScroll = (listScroll + listFlingV * dt / 1000f).coerceIn(0f, max)
            listFlingV *= exp(-dt / 400f)
            if (abs(listFlingV) < 30f || listScroll <= 0f || listScroll >= max) {
                listFlingV = 0f
            } else {
                postOnAnimation(this)
            }
            invalidate()
        }
    }

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
            if (isPlaying && !scrubbing && !listSeeking) {
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
        WalkmanNotificationListener.onQueueChanged = { onQueueUpdated() }

        // Если сессия уже найдена, сразу подтянем текущий трек
        WalkmanNotificationListener.activeController?.let {
            applyMetadata(it.metadata)
            applyState(it.playbackState)
        }

        rebuildTracks()
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
        WalkmanNotificationListener.onQueueChanged = null
        sheetAnimator?.cancel()
        loadGeneration++
        removeCallbacks(flingRunnable)
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
        // Заглушку Sony (серая нота) не показываем
        val usableRaw = raw?.takeIf { !looksLikePlaceholder(it) }
        coverBitmap = usableRaw?.let { makeCover(it) }
        coverSquare = usableRaw?.let { makeSquareCover(it) }
        if (raw == null) lastCoverInfo = "Обложки нет в метаданных"

        titleShown = fit(songTitle, flexPaint, Design.TEXT_MAX_W)
        artistShown = fit(artistName, flexPaint, Design.TEXT_MAX_W)

        if (tracks.size <= 1) rebuildTracks()
        refreshHeader(meta)

        refreshTrackText(meta)
        shownSecond = -1L
        invalidate()
    }

    private fun applyState(state: PlaybackState?) {
        if (state == null) return
        isPlaying = state.state == PlaybackState.STATE_PLAYING
        activeQueueId = state.activeQueueItemId

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

    // Плеер Sony для альбомов без обложки отдаёт свою серую картинку с нотой.
    // Узнаём её по виду: почти нет цвета и небольшой контраст.
    private var lastCoverInfo = "Данных об обложке пока нет"

    private fun looksLikePlaceholder(bmp: Bitmap): Boolean {
        return try {
            val n = 32
            val small = Bitmap.createScaledBitmap(bmp, n, n, true)
            val px = IntArray(n * n)
            small.getPixels(px, 0, n, 0, 0, n, n)
            val lums = IntArray(n * n)
            var chromaSum = 0L
            for (i in px.indices) {
                val c = px[i]
                val r = Color.red(c)
                val g = Color.green(c)
                val b = Color.blue(c)
                chromaSum += (maxOf(r, g, b) - minOf(r, g, b))
                lums[i] = (r * 299 + g * 587 + b * 114) / 1000
            }
            lums.sort()
            val chroma = chromaSum / (n * n)
            val range = lums[(n * n * 0.95).toInt()] - lums[(n * n * 0.05).toInt()]
            val hidden = chroma < Design.PLACEHOLDER_MAX_CHROMA && range < Design.PLACEHOLDER_MAX_RANGE
            lastCoverInfo = "Обложка ${bmp.width}x${bmp.height}, цвет $chroma (порог ${Design.PLACEHOLDER_MAX_CHROMA}), " +
                "контраст $range (порог ${Design.PLACEHOLDER_MAX_RANGE}), " + if (hidden) "скрыта как заглушка" else "показана"
            hidden
        } catch (e: Exception) {
            false
        }
    }

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

        // Когда список закрывает весь экран, главный экран под ним не рисуем
        if (!(sheetVisible && sheetY <= 0f)) {
            drawTopRow(canvas)
            drawDisc(canvas)
            drawTitleRow(canvas)
            drawVolume(canvas)
        }
        if (sheetVisible) drawSheet(canvas)

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
        // Вторая линия только внутри внутреннего круга (так на макете)
        canvas.drawLine(cx, cy - Design.COVER_R, cx, cy + Design.COVER_R, linePaint)
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

        if (sheetVisible) return handleSheetTouch(event, x, y)

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
            colorDownTime = SystemClock.uptimeMillis()
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
            } else {
                // Поле с названием: по нажатию откроется список треков
                mode = Mode.TITLE
                downX = x
                downY = y
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
            volDownTime = SystemClock.uptimeMillis()
            volMoved = false
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
                if (abs(p - volStartProgress) > 0.01f) volMoved = true
                val target = (volStartPercent + (p - volStartProgress) * 100f * Design.VOLUME_GAIN)
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
                // Долгое удержание без движения: показать сведения о громкости (для настройки)
                if (!volMoved && !cancelled &&
                    SystemClock.uptimeMillis() - volDownTime >= Design.CLONG_PRESS_MS
                ) {
                    Toast.makeText(context, volumeDiagnostics(), Toast.LENGTH_LONG).show()
                }
                finishVolumeDrag()
            }

            Mode.TITLE -> {
                if (!cancelled && hypot(x - downX, y - downY) < touchSlop * 2f) openSheet()
            }

            Mode.COLOR -> {
                if (!cancelled && hypot(x - Design.C_CX, y - Design.C_CY) <= Design.C_R + 12f) {
                    if (SystemClock.uptimeMillis() - colorDownTime >= Design.CLONG_PRESS_MS) {
                        // Долгое нажатие: показать данные об обложке (для настройки)
                        Toast.makeText(context, lastCoverInfo + "\n" + queueInfo(), Toast.LENGTH_LONG).show()
                    } else {
                        showColorPickerDialog()
                    }
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
            val flags = if (Design.VOLUME_SHOW_PANEL) AudioManager.FLAG_SHOW_UI else 0
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, flags)
        } catch (e: Exception) {
        }

        // Если сессия плеера использует собственную громкость, передаём значение и ей
        try {
            val ctrl = WalkmanNotificationListener.activeController
            val info = ctrl?.playbackInfo
            if (ctrl != null && info != null &&
                info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE &&
                info.maxVolume > 0
            ) {
                ctrl.setVolumeTo((percent * info.maxVolume / 100f).roundToInt(), 0)
            }
        } catch (e: Exception) {
        }
    }

    // Сведения о громкости для настройки (долгое удержание пальца на полукруге)
    private fun volumeDiagnostics(): String {
        val sb = StringBuilder()
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            sb.append("Громкость: $cur из $max")
        } catch (e: Exception) {
        }
        try {
            sb.append(", фиксированная: ").append(audioManager.isVolumeFixed)
        } catch (e: Exception) {
        }
        try {
            val devs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .joinToString(",") { it.type.toString() }
            sb.append("\nВыходы (типы): ").append(devs)
        } catch (e: Exception) {
        }
        try {
            val info = WalkmanNotificationListener.activeController?.playbackInfo
            if (info != null) {
                if (info.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE) {
                    sb.append("\nСессия плеера: своя громкость ${info.currentVolume} из ${info.maxVolume}")
                } else {
                    sb.append("\nСессия плеера: системная громкость")
                }
            }
        } catch (e: Exception) {
        }
        return sb.toString()
    }

    private fun queueInfo(): String {
        return try {
            val ctrl = WalkmanNotificationListener.activeController
            val n = ctrl?.queue?.size ?: 0
            val acts = ctrl?.playbackState?.actions ?: 0L
            val canJump = (acts and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) != 0L
            "В очереди $n треков, переход по номеру: " + if (canJump) "есть" else "нет"
        } catch (e: Exception) {
            "Очередь недоступна"
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
    // Список треков: данные
    // ------------------------------------------------------------------
    fun handleBack(): Boolean {
        if (!sheetVisible) return false
        closeSheet()
        return true
    }

    // Пользователь ответил на запрос доступа к файлам: пробуем снова прочитать время и формат
    fun onPermissionResult() {
        formatKey = null
        loadDurationsAsync(tracks)
        loadFormatAsync(currentTrackUri())
    }

    private fun onQueueUpdated() {
        refreshTrackText(WalkmanNotificationListener.activeController?.metadata)
        rebuildTracks()
    }

    private fun rebuildTracks() {
        val q = try {
            WalkmanNotificationListener.activeController?.queue
        } catch (e: Exception) {
            null
        }
        var list = TrackLoader.fromQueue(q)
        // Если плеер не отдаёт очередь, показываем хотя бы играющий трек
        if (list.isEmpty()) list = listOf(TrackItem(-1L, songTitle, null, trackDurationMs))
        tracks = list
        if (sheetVisible) loadDurationsAsync(list)
        loadFormatAsync(currentTrackUri())
        invalidate()
    }

    private fun activeIndex(): Int {
        if (tracks.isEmpty()) return -1
        val byId = tracks.indexOfFirst { it.queueId >= 0 && it.queueId == activeQueueId }
        if (byId >= 0) return byId
        return tracks.indexOfFirst { it.title == songTitle }
    }

    private fun refreshHeader(meta: MediaMetadata) {
        hdrArtistShown = fit(artistName, flexPaint, Design.SHEET_TEXT_MAX_W)
        val album = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        hdrAlbumShown = fit(album, flexPaint, Design.SHEET_TEXT_MAX_W)

        val year = meta.getLong(MediaMetadata.METADATA_KEY_YEAR)
        hdrYear = if (year > 0) {
            year.toString()
        } else {
            val first = (meta.getString(MediaMetadata.METADATA_KEY_DATE) ?: "").take(4)
            if (first.length == 4 && first.all { ch -> ch.isDigit() }) first else ""
        }
        loadFormatAsync(currentTrackUri(meta))
    }

    private fun currentTrackUri(
        meta: MediaMetadata? = WalkmanNotificationListener.activeController?.metadata
    ): Uri? {
        try {
            val s = meta?.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            if (!s.isNullOrBlank()) return Uri.parse(s)
            val idx = activeIndex()
            if (idx >= 0) return tracks[idx].uri
        } catch (e: Exception) {
        }
        return null
    }

    // Квадратная обложка для шапки списка (с обрезкой по центру)
    private fun makeSquareCover(bmp: Bitmap): Bitmap {
        val size = (Design.W - Design.SHEET_COVER_X).toInt()
        val side = min(bmp.width, bmp.height)
        val left = (bmp.width - side) / 2
        val top = (bmp.height - side) / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(bmp, Rect(left, top, left + side, top + side), Rect(0, 0, size, size), coverPaint)
        return out
    }

    private fun loadFormatAsync(uri: Uri?) {
        if (uri == null) {
            formatKey = null
            hdrFormat = ""
            return
        }
        val key = uri.toString()
        if (key == formatKey) return
        formatKey = key
        hdrFormat = ""
        Thread {
            val f = TrackLoader.readFormat(context, uri)
            mainHandler.post {
                if (formatKey == key) {
                    hdrFormat = f ?: ""
                    invalidate()
                }
            }
        }.start()
    }

    private fun loadDurationsAsync(list: List<TrackItem>) {
        val pending = list.filter { it.durationMs <= 0 && it.uri != null }
        if (pending.isEmpty()) return
        val gen = ++loadGeneration
        Thread {
            for (t in pending) {
                if (gen != loadGeneration) return@Thread
                val d = TrackLoader.readDuration(context, t.uri!!)
                if (d > 0) {
                    t.durationMs = d
                    mainHandler.post { invalidate() }
                }
            }
        }.start()
    }

    // Доступ к музыкальным файлам нужен только чтобы показать время и формат.
    // Спрашиваем один раз и только если плеер отдал адреса файлов.
    private fun ensureAudioPermission() {
        try {
            if (tracks.none { it.uri != null } && currentTrackUri() == null) return
            if (context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED) return
            if (prefs.getBoolean("audio_perm_asked", false)) return
            prefs.edit().putBoolean("audio_perm_asked", true).apply()
            (context as? Activity)?.requestPermissions(arrayOf(Manifest.permission.READ_MEDIA_AUDIO), 7)
        } catch (e: Exception) {
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun maxScroll(): Float {
        val contentH = Design.SHEET_HEADER_BOTTOM + tracks.size * Design.ROW_H
        return (contentH - Design.H).coerceAtLeast(0f)
    }

    // ------------------------------------------------------------------
    // Список треков: открытие и закрытие
    // ------------------------------------------------------------------
    private fun openSheet() {
        if (sheetVisible) return
        rebuildTracks()
        ensureAudioPermission()
        loadDurationsAsync(tracks)

        // Сразу показать играющий трек, если он не помещается на экране
        listScroll = 0f
        val idx = activeIndex()
        if (idx >= 0) {
            val bottom = Design.SHEET_HEADER_BOTTOM + (idx + 1) * Design.ROW_H
            if (bottom > Design.H) {
                listScroll = (bottom - Design.H + Design.ROW_H).coerceIn(0f, maxScroll())
            }
        }

        sheetVisible = true
        sheetY = Design.H
        animateSheet(0f, Design.SHEET_OPEN_MS, null)
    }

    private fun closeSheet() {
        animateSheet(Design.H, Design.SHEET_CLOSE_MS) {
            sheetVisible = false
            listFlingV = 0f
            invalidate()
        }
    }

    private fun animateSheet(to: Float, durationMs: Long, endAction: (() -> Unit)?) {
        sheetAnimator?.cancel()
        val a = ValueAnimator.ofFloat(sheetY, to)
        a.duration = durationMs
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            sheetY = it.animatedValue as Float
            invalidate()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            private var cancelled = false

            override fun onAnimationCancel(animation: Animator) {
                cancelled = true
            }

            override fun onAnimationEnd(animation: Animator) {
                if (!cancelled) endAction?.invoke()
            }
        })
        sheetAnimator = a
        a.start()
    }

    // ------------------------------------------------------------------
    // Список треков: рисование
    // ------------------------------------------------------------------
    private fun drawSheet(canvas: Canvas) {
        canvas.save()
        canvas.translate(0f, sheetY)
        canvas.drawRect(0f, 0f, Design.W, Design.H, blackPaint)

        // Прокручиваемая часть: шапка и строки с песнями
        canvas.save()
        canvas.clipRect(0f, Design.TOP_H, Design.W, Design.H)
        canvas.translate(0f, -listScroll)
        drawSheetHeader(canvas)
        drawSheetRows(canvas)
        canvas.restore()

        // Верхняя строка B / M / SD всегда на месте
        canvas.drawRect(0f, 0f, Design.W, Design.TOP_H, blackPaint)
        drawTopRow(canvas)

        canvas.restore()
    }

    private fun drawSheetHeader(canvas: Canvas) {
        val top = Design.TOP_H
        val bottom = Design.SHEET_HEADER_BOTTOM

        canvas.drawText(hdrArtistShown, Design.SHEET_TEXT_X, Design.SHEET_LINE1_BASE, flexPaint)
        canvas.drawText(hdrAlbumShown, Design.SHEET_TEXT_X, Design.SHEET_LINE2_BASE, flexPaint)
        canvas.drawText(hdrYear, Design.SHEET_TEXT_X, Design.SHEET_LINE3_BASE, flexPaint)
        if (hdrFormat.isNotEmpty()) {
            canvas.drawText(hdrFormat, Design.SHEET_TEXT_X, Design.SHEET_FORMAT_BASE, smallMonoPaint)
        }

        val sq = coverSquare
        if (sq != null) {
            canvas.save()
            canvas.clipRect(Design.SHEET_COVER_X, top, Design.W, bottom)
            canvas.drawBitmap(sq, Design.SHEET_COVER_X, top, coverPaint)
            canvas.restore()
        } else {
            // Обложки нет: диагональ и красный квадрат
            canvas.drawLine(Design.SHEET_COVER_X, bottom, Design.W, top, linePaint)
            val cx = (Design.SHEET_COVER_X + Design.W) / 2f
            val cy = (top + bottom) / 2f
            val h = Design.PLACEHOLDER_SQ / 2f
            canvas.drawRect(cx - h, cy - h, cx + h, cy + h, accentPaint)
        }

        canvas.drawLine(Design.SHEET_COVER_X, top, Design.SHEET_COVER_X, bottom, linePaint)
        canvas.drawLine(0f, bottom, Design.W, bottom, linePaint)
    }

    private fun drawSheetRows(canvas: Canvas) {
        val list = tracks
        if (list.isEmpty()) return

        val base0 = Design.SHEET_HEADER_BOTTOM
        val visTop = listScroll + Design.TOP_H
        val visBottom = listScroll + Design.H
        val first = maxOf(0, ((visTop - base0) / Design.ROW_H).toInt())
        val last = minOf(list.size - 1, ((visBottom - base0) / Design.ROW_H).toInt())
        val active = activeIndex()

        for (i in first..last) {
            val t = list[i]
            val top = base0 + i * Design.ROW_H
            val bot = top + Design.ROW_H

            // Играющая песня постепенно заливается акцентным цветом
            if (i == active) {
                val p = if (trackDurationMs > 0) {
                    (currentPositionMs.toFloat() / trackDurationMs).coerceIn(0f, 1f)
                } else {
                    0f
                }
                if (p > 0f) canvas.drawRect(0f, top, Design.W * p, bot, accentPaint)
            }

            val base = top + Design.ROW_BASE
            canvas.drawText((i + 1).toString(), Design.ROW_NUM_X, base, monoPaint)

            var shown = t.shownTitle
            if (shown == null) {
                shown = fit(t.title, flexPaint, Design.ROW_TITLE_MAX_W)
                t.shownTitle = shown
            }
            canvas.drawText(shown, Design.ROW_TITLE_X, base, flexPaint)

            val dur = if (i == active && trackDurationMs > 0) trackDurationMs else t.durationMs
            if (dur > 0) drawRight(canvas, formatTime(dur), Design.ROW_TIME_RIGHT, base, monoPaint)

            canvas.drawLine(0f, bot, Design.W, bot, linePaint)
        }
    }

    // ------------------------------------------------------------------
    // Список треков: касания
    // ------------------------------------------------------------------

    // Номер строки под пальцем (y в координатах экрана) или -1
    private fun rowAt(y: Float): Int {
        val local = y - sheetY
        if (local <= Design.TOP_H) return -1
        val cy = local + listScroll
        if (cy < Design.SHEET_HEADER_BOTTOM) return -1
        val i = ((cy - Design.SHEET_HEADER_BOTTOM) / Design.ROW_H).toInt()
        return if (i in tracks.indices) i else -1
    }

    private fun handleSheetTouch(event: MotionEvent, x: Float, y: Float): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (sheetAnimator?.isRunning == true) sheetAnimator?.cancel()
                removeCallbacks(flingRunnable)
                listFlingV = 0f
                sg = SheetGesture.PENDING
                sgDownX = x
                sgDownY = y
                sgLastY = y
                sgAtTop = listScroll <= 1f
                val act = activeIndex()
                sgOnActive = act >= 0 && rowAt(y) == act
                velocity?.recycle()
                velocity = VelocityTracker.obtain().also { it.addMovement(event) }
            }

            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = x - sgDownX
                val dy = y - sgDownY

                if (sg == SheetGesture.PENDING && hypot(dx, dy) > touchSlop) {
                    sg = when {
                        sgOnActive && abs(dx) > abs(dy) -> SheetGesture.SEEK
                        abs(dy) >= abs(dx) && sgAtTop && dy > 0f -> SheetGesture.DRAG_SHEET
                        abs(dy) >= abs(dx) -> SheetGesture.SCROLL
                        else -> SheetGesture.IGNORE
                    }
                    if (sg == SheetGesture.SEEK) listSeeking = true
                }

                val step = y - sgLastY
                when (sg) {
                    SheetGesture.SCROLL -> {
                        listScroll = (listScroll - step).coerceIn(0f, maxScroll())
                    }

                    SheetGesture.DRAG_SHEET -> {
                        sheetY = (sheetY + step).coerceAtLeast(0f)
                        // Палец пошёл обратно вверх: дальше просто прокручиваем список
                        if (sheetY <= 0f && step < 0f) sg = SheetGesture.SCROLL
                    }

                    SheetGesture.SEEK -> seekPreview(x)

                    else -> {
                    }
                }
                sgLastY = y
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000)
                val vy = (velocity?.yVelocity ?: 0f) * Design.H / height
                velocity?.recycle()
                velocity = null

                when (sg) {
                    SheetGesture.PENDING -> {
                        if (!cancelled) onSheetTap(x, y)
                    }

                    SheetGesture.SEEK -> {
                        if (!cancelled) commitSeek()
                    }

                    SheetGesture.SCROLL -> startFling(-vy)

                    SheetGesture.DRAG_SHEET -> {
                        val far = sheetY > Design.H * Design.SHEET_DISMISS_FRACTION
                        val fast = vy > Design.SHEET_DISMISS_VELOCITY
                        if (!cancelled && (far || fast)) {
                            closeSheet()
                        } else {
                            animateSheet(0f, 180L, null)
                        }
                    }

                    else -> {
                    }
                }

                listSeeking = false
                // Если экран остановился не в крайнем положении, возвращаем его на место
                if (sg != SheetGesture.DRAG_SHEET && sheetY > 0f && sheetVisible &&
                    sheetAnimator?.isRunning != true
                ) {
                    animateSheet(0f, 150L, null)
                }
                sg = SheetGesture.NONE
                invalidate()
            }
        }
        return true
    }

    private fun onSheetTap(x: Float, y: Float) {
        val i = rowAt(y)
        if (i < 0) return
        if (i == activeIndex()) {
            // Нажатие на играющую песню работает как перемотка в это место
            seekPreview(x)
            commitSeek()
        } else {
            playTrackAt(i)
        }
    }

    // Перемотка: положение пальца по горизонтали = положение в треке
    private fun seekPreview(x: Float) {
        if (trackDurationMs > 0) {
            currentPositionMs = (x / Design.W * trackDurationMs).toLong().coerceIn(0L, trackDurationMs)
            shownSecond = -1L
        }
    }

    private fun commitSeek() {
        WalkmanNotificationListener.activeController?.transportControls?.seekTo(currentPositionMs)
    }

    private fun startFling(v: Float) {
        if (abs(v) < Design.FLING_MIN_V) return
        listFlingV = v
        flingLast = SystemClock.uptimeMillis()
        removeCallbacks(flingRunnable)
        postOnAnimation(flingRunnable)
    }

    private fun playTrackAt(index: Int) {
        val ctrl = WalkmanNotificationListener.activeController ?: return
        val item = tracks.getOrNull(index) ?: return
        if (item.queueId < 0) return
        val actions = ctrl.playbackState?.actions ?: 0L
        if ((actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) != 0L) {
            ctrl.transportControls.skipToQueueItem(item.queueId)
        } else {
            // Плеер не умеет прыгать на песню по номеру: листаем кнопками "вперёд" и "назад"
            val from = activeIndex()
            if (from >= 0) stepToTrack(index - from)
        }
    }

    private fun stepToTrack(diff: Int) {
        if (diff == 0) return
        val tc = WalkmanNotificationListener.activeController?.transportControls ?: return
        var left = abs(diff).coerceAtMost(60)
        val forward = diff > 0
        val r = object : Runnable {
            override fun run() {
                if (left <= 0) return
                left--
                if (forward) tc.skipToNext() else tc.skipToPrevious()
                if (left > 0) mainHandler.postDelayed(this, 250L)
            }
        }
        r.run()
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
