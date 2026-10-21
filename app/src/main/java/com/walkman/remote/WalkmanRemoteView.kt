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
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.Bundle
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

    private enum class SheetGesture { NONE, PENDING, SCROLL, DRAG_SHEET, DRAG_SIDE, SEEK, IGNORE }

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

    // ------------------------------------------------------------------
    // Список треков: состояние
    // ------------------------------------------------------------------
    private var sheetVisible = false
    private var sheetY = Design.H            // 0 = экран открыт, H = спрятан внизу
    private var sheetX = 0f                  // сдвиг вправо при закрытии смахиванием вбок
    private var sheetAnimator: ValueAnimator? = null
    private var sheetXAnimator: ValueAnimator? = null
    private var listScroll = 0f
    private var listFlingV = 0f
    private var flingLast = 0L
    private var listSeeking = false

    private var tracks: List<TrackItem> = emptyList()
    private var activeQueueId = -1L
    private var loadGeneration = 0
    private var formatKey: String? = null

    // Если плеер не отдаёт очередь, список строится из альбома в библиотеке
    private var currentAlbum = ""
    private var currentTrackNo = 0
    private var albumTracks: List<TrackItem> = emptyList()
    private var albumKey: String? = null
    private var albumLoading = false

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
    private var spinK = 0f            // 0..1: разгон и торможение диска
    private var flipAngle = 0f        // дополнительный оборот при смене трека
    private var trackKey = ""

    private val frameRunnable = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            val dt = now - lastFrameTime
            lastFrameTime = now

            val goal = if (isPlaying && !scrubbing && !listSeeking) 1f else 0f
            if (scrubbing || listSeeking) spinK = 0f
            val stepK = dt / Design.DISC_RAMP_MS
            spinK = if (spinK < goal) min(goal, spinK + stepK) else maxOf(goal, spinK - stepK)

            if (!scrubbing && !listSeeking) {
                val speed = spinK * spinK * (3f - 2f * spinK)
                discAngle = (discAngle + dt * Design.DISC_DEG_PER_MS * speed) % 360f
                if (isPlaying) currentPositionMs = (currentPositionMs + dt).coerceIn(0L, maxPosition())
            }
            invalidate()
            if (isPlaying || spinK > 0f) {
                postOnAnimation(this)
            } else {
                frameLoopRunning = false
            }
        }
    }

    // Быстрый оборот диска: dir = 1 по часовой стрелке, -1 против.
    // Считаем по часам сами (системные анимации на плеере могут быть отключены).
    private var flipStart = 0L
    private var flipDir = 1

    private val flipRunnable = object : Runnable {
        override fun run() {
            val t = ((SystemClock.uptimeMillis() - flipStart).toFloat() / Design.DISC_FLIP_MS).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            flipAngle = if (t >= 1f) 0f else 360f * flipDir * eased
            invalidate()
            if (t < 1f) postOnAnimation(this)
        }
    }

    private fun startFlip(dir: Int) {
        if (flipAngle != 0f) return
        flipDir = dir
        flipStart = SystemClock.uptimeMillis()
        flipAngle = 0.001f * dir
        removeCallbacks(flipRunnable)
        postOnAnimation(flipRunnable)
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
            if (mode != Mode.VOLUME) {
                if (housekeepingTick % 3 == 0) pollIzmVolume()
                syncVolumeFromSystem()
            }
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

        // События громкости Sony (кнопки на плеере): двигаем шкалу вслед за ними
        try {
            val f = IntentFilter()
            f.addAction("com.sony.walkman.intent.action.MASTER_VOLUME_CHANGED")
            f.addAction("com.sony.walkman.intent.action.VOLUME_KEY_PRESSED")
            context.registerReceiver(izmReceiver, f)
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
        try {
            context.unregisterReceiver(izmReceiver)
        } catch (e: Exception) {
        }
        WalkmanNotificationListener.onMetadataChanged = null
        WalkmanNotificationListener.onStateChanged = null
        WalkmanNotificationListener.onQueueChanged = null
        sheetAnimator?.cancel()
        sheetXAnimator?.cancel()
        removeCallbacks(flipRunnable)
        loadGeneration++
        stepTarget = null
        mainHandler.removeCallbacks(stepRunnable)
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

    // Шкала показывает громкость Sony, если её уровень известен
    private fun syncVolumeFromSystem() {
        if (izmCur < 0 || mode == Mode.VOLUME) return
        val p = (izmCur * 100f / Design.IZM_MAX).roundToInt().coerceIn(0, 100)
        if (p != volumePercent) {
            volumePercent = p
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

        titleShown = fit(songTitle, flexPaint, Design.TEXT_MAX_W)
        artistShown = fit(artistName, flexPaint, Design.TEXT_MAX_W)

        currentAlbum = meta.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val newNo = meta.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER).toInt()
        val key = "$songTitle|$artistName|$newNo"
        // Трек сменился не по нашей команде (кнопки плеера, конец песни): оборот диска
        if (trackKey.isNotEmpty() && key != trackKey && stepTarget == null) {
            startFlip(if (newNo in 1 until currentTrackNo) -1 else 1)
        }
        trackKey = key
        currentTrackNo = newNo
        rebuildTracks()
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
            chroma < Design.PLACEHOLDER_MAX_CHROMA && range < Design.PLACEHOLDER_MAX_RANGE
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
        if (!(sheetVisible && sheetY <= 0f && sheetX <= 0f)) {
            drawTopRow(canvas)
            drawDisc(canvas)
            drawTitleRow(canvas)
            drawVolume(canvas)
        }
        if (sheetVisible) {
            drawSheet(canvas)
            // Верхняя строка одна на оба экрана и всегда на месте
            canvas.drawRect(0f, 0f, Design.W, Design.TOP_H, blackPaint)
            drawTopRow(canvas)
        }

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
        canvas.rotate(discAngle + flipAngle, cx, cy)
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
            // Уровень Sony пока неизвестен: считаем от нуля (так громче, чем было, не станет)
            if (izmCur < 0) volumePercent = 0
            izmLastSent = -1
            volStartPercent = volumePercent
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
                val target = (volStartPercent + (p - volStartProgress) * 100f * Design.VOLUME_GAIN)
                    .coerceIn(0f, 100f).roundToInt()
                if (target != volumePercent) {
                    volumePercent = target
                    setIzmVolume(target)
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
                    startFlip(if (mode == Mode.PREV) -1 else 1)
                }
            }

            Mode.TITLE -> {
                if (!cancelled && hypot(x - downX, y - downY) < touchSlop * 2f) openSheet()
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
    // Громкость: плеер Sony регулирует звук параметром izm_master_volume
    // ------------------------------------------------------------------
    private var izmCur = -1                 // текущий уровень, -1 = пока неизвестен
    private var izmLastSent = -1
    private var izmGetWorks = true
    private var izmGetFails = 0

    // Кнопки громкости на корпусе: плеер присылает новый уровень, двигаем шкалу
    private val izmReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            intent ?: return
            if (intent.action?.endsWith("MASTER_VOLUME_CHANGED") == true) {
                try {
                    val ex = intent.extras
                    if (ex != null) {
                        for (k in ex.keySet()) {
                            if (!(k.contains("volume", true) || k.contains("level", true) || k.contains("value", true))) continue
                            @Suppress("DEPRECATION")
                            val v = ex.get(k)
                            val num: Int? = when (v) {
                                is Int -> v
                                is Long -> v.toInt()
                                else -> null
                            }
                            if (num != null && num >= 0) {
                                izmCur = num.coerceIn(0, Design.IZM_MAX)
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                }
            }
            syncVolumeFromSystem()
        }
    }

    // Читаем текущий уровень у аудио-железа (если оно отвечает на такой запрос)
    private fun pollIzmVolume() {
        if (!izmGetWorks) return
        try {
            val r = audioManager.getParameters("izm_master_volume") ?: ""
            val v = r.substringAfter("izm_master_volume=", "").takeWhile { it.isDigit() }.toIntOrNull()
            if (v != null) {
                izmCur = v.coerceIn(0, Design.IZM_MAX)
                izmGetFails = 0
            } else if (++izmGetFails >= 3) {
                izmGetWorks = false
            }
        } catch (e: Exception) {
            izmGetWorks = false
        }
    }

    private fun setIzmVolume(percent: Int) {
        val v = (percent * Design.IZM_MAX / 100f).roundToInt().coerceIn(0, Design.IZM_MAX)
        if (v == izmLastSent) return
        izmLastSent = v
        try {
            audioManager.setParameters("izm_master_volume=$v")
            izmCur = v
        } catch (e: Exception) {
        }
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
        albumKey = null
        albumTracks = emptyList()
        rebuildTracks()
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
        if (list.size <= 1) {
            val key = albumKeyNow()
            if (key != null && key == albumKey && albumTracks.isNotEmpty()) {
                list = albumTracks
            } else {
                // Крайний случай: хотя бы играющий трек
                list = listOf(TrackItem(-1L, songTitle, null, trackDurationMs))
                loadAlbumAsync()
            }
        }
        tracks = list
        if (sheetVisible) loadDurationsAsync(list)
        loadFormatAsync(currentTrackUri())
        invalidate()
    }

    private fun activeIndex(): Int {
        if (tracks.isEmpty()) return -1
        val byId = tracks.indexOfFirst { it.queueId >= 0 && it.queueId == activeQueueId }
        if (byId >= 0) return byId
        val name = songTitle.trim()
        val byTitle = tracks.indexOfFirst { it.title.trim().equals(name, ignoreCase = true) }
        if (byTitle >= 0) return byTitle
        if (currentTrackNo > 0) {
            // В библиотеке номер песни может храниться как 1001 (диск 1, песня 1)
            return tracks.indexOfFirst { it.trackNo > 0 && it.trackNo % 1000 == currentTrackNo }
        }
        return -1
    }

    private fun albumKeyNow(): String? =
        if (currentAlbum.isBlank()) null else currentAlbum + "|" + artistName

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasAudioPermission(): Boolean = try {
        context.checkSelfPermission(audioPermission()) == PackageManager.PERMISSION_GRANTED
    } catch (e: Exception) {
        false
    }

    private fun loadAlbumAsync() {
        val key = albumKeyNow() ?: return
        if (albumLoading || key == albumKey) return
        if (!hasAudioPermission()) return
        albumLoading = true
        val album = currentAlbum
        val artist = artistName
        Thread {
            val res = TrackLoader.fromAlbum(context, album, artist)
            mainHandler.post {
                albumLoading = false
                albumTracks = res
                albumKey = key
                // Если за это время сменился альбом, rebuildTracks запустит новую загрузку
                rebuildTracks()
            }
        }.start()
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
            if (hasAudioPermission()) return
            val asked = prefs.getInt("audio_perm_asks", 0)
            if (asked >= 2) return
            prefs.edit().putInt("audio_perm_asks", asked + 1).apply()
            (context as? Activity)?.requestPermissions(arrayOf(audioPermission()), 7)
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
        sheetX = 0f
        animateSheet(0f, Design.SHEET_OPEN_MS, null)
    }

    private fun closeSheet() {
        animateSheet(Design.H, Design.SHEET_CLOSE_MS) {
            sheetVisible = false
            listFlingV = 0f
            invalidate()
        }
    }

    // Закрытие смахиванием вправо
    private fun closeSheetSide() {
        animateSheetX(Design.W, Design.SHEET_CLOSE_MS) {
            sheetVisible = false
            sheetX = 0f
            listFlingV = 0f
            invalidate()
        }
    }

    private fun animateSheetX(to: Float, durationMs: Long, endAction: (() -> Unit)?) {
        sheetXAnimator?.cancel()
        val a = ValueAnimator.ofFloat(sheetX, to)
        a.duration = durationMs
        a.interpolator = DecelerateInterpolator()
        a.addUpdateListener {
            sheetX = it.animatedValue as Float
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
        sheetXAnimator = a
        a.start()
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
        canvas.translate(sheetX, sheetY)
        canvas.drawRect(0f, 0f, Design.W, Design.H, blackPaint)

        // Шапка и строки с песнями (верхнюю строку B / M / SD рисует onDraw поверх)
        canvas.save()
        canvas.clipRect(0f, 0f, Design.W, Design.H)
        canvas.translate(0f, -listScroll)
        drawSheetHeader(canvas)
        drawSheetRows(canvas)
        canvas.restore()

        canvas.restore()
    }

    private fun drawSheetHeader(canvas: Canvas) {
        val top = Design.TOP_H
        val bottom = Design.SHEET_HEADER_BOTTOM

        canvas.drawText(hdrArtistShown, Design.SHEET_TEXT_X, Design.SHEET_LINE1_BASE, flexPaint)
        canvas.drawText(hdrAlbumShown, Design.SHEET_TEXT_X, Design.SHEET_LINE2_BASE, flexPaint)
        val yearFromLib = (tracks.getOrNull(activeIndex())?.year ?: 0)
        val yearText = if (hdrYear.isNotEmpty()) hdrYear else if (yearFromLib > 0) yearFromLib.toString() else ""
        canvas.drawText(yearText, Design.SHEET_TEXT_X, Design.SHEET_LINE3_BASE, flexPaint)
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
        val visTop = listScroll
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
                if (sheetXAnimator?.isRunning == true) sheetXAnimator?.cancel()
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
                        abs(dx) > abs(dy) && dx > 0f -> SheetGesture.DRAG_SIDE
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

                    SheetGesture.DRAG_SIDE -> sheetX = (x - sgDownX).coerceAtLeast(0f)

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
                val vx = (velocity?.xVelocity ?: 0f) * Design.W / width
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

                    SheetGesture.DRAG_SIDE -> {
                        val far = sheetX > Design.W * Design.SHEET_SIDE_FRACTION
                        val fast = vx > Design.SHEET_SIDE_VELOCITY
                        if (!cancelled && (far || fast)) {
                            closeSheetSide()
                        } else {
                            animateSheetX(0f, 180L, null)
                        }
                    }

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
                if (sg != SheetGesture.DRAG_SHEET && sg != SheetGesture.DRAG_SIDE && sheetY > 0f && sheetVisible &&
                    sheetAnimator?.isRunning != true
                ) {
                    animateSheet(0f, 150L, null)
                }
                if (sg != SheetGesture.DRAG_SIDE && sheetX > 0f && sheetVisible &&
                    sheetXAnimator?.isRunning != true
                ) {
                    animateSheetX(0f, 150L, null)
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
        val actions = ctrl.playbackState?.actions ?: 0L
        if (!isPlaying) ctrl.transportControls.play()
        if (item.queueId >= 0 && (actions and PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM) != 0L) {
            ctrl.transportControls.skipToQueueItem(item.queueId)
            return
        }

        // Очереди нет: листаем кнопками "вперёд" и "назад" (порядок как в альбоме)
        val from = activeIndex()
        if (from >= 0 && from != index) {
            startFlip(if (index > from) 1 else -1)
            stepToTrack(index, from)
        }
    }

    // Переход на выбранную песню. У плеера Sony есть только "вперёд" и "назад", поэтому
    // команды идут сериями с опережением (не ждём каждого переключения), а в конце
    // проверяем результат и при необходимости досылаем недостающие шаги.
    private var stepTarget: TrackItem? = null
    private var stepDir = 1
    private var stepStartIdx = 0
    private var stepNeeded = 0
    private var stepRestart = 0
    private var stepIssued = 0
    private var stepPlans = 0
    private var stepLastIssueAt = 0L
    private var stepLastChangeAt = 0L
    private var stepLastCur = -1

    private fun planSteps(cur: Int, targetIdx: Int) {
        stepStartIdx = cur
        stepDir = if (targetIdx > cur) 1 else -1
        // "Назад" на середине песни только начинает её заново: нужен лишний шаг
        stepRestart = if (stepDir < 0 && currentPositionMs > Design.PREV_RESTART_MS) 1 else 0
        stepNeeded = abs(targetIdx - cur) + stepRestart
        stepIssued = 0
        stepLastChangeAt = SystemClock.uptimeMillis()
        stepPlans++
    }

    private val stepRunnable = object : Runnable {
        override fun run() {
            val target = stepTarget ?: return
            val tc = WalkmanNotificationListener.activeController?.transportControls
            if (!isAttachedToWindow || tc == null) {
                stepTarget = null
                return
            }
            val targetIdx = tracks.indexOfFirst { it.title == target.title && it.trackNo == target.trackNo }
            val cur = activeIndex()
            if (targetIdx < 0 || cur < 0) {
                stepTarget = null
                return
            }
            val now = SystemClock.uptimeMillis()

            if (cur != stepLastCur) {
                // Индекс скакнул дальше, чем мы успели отправить команд: включён shuffle
                if (stepLastCur >= 0 && abs(cur - stepLastCur) > Design.STEP_LOOKAHEAD) {
                    Toast.makeText(context, "Порядок треков не совпал. Выключен ли shuffle в плеере?", Toast.LENGTH_LONG).show()
                    stepTarget = null
                    return
                }
                stepLastCur = cur
                stepLastChangeAt = now
            }
            if (cur == targetIdx) {
                stepTarget = null
                if (!isPlaying) tc.play()
                return
            }

            var progress = (cur - stepStartIdx) * stepDir
            var inFlight = (stepIssued - progress - stepRestart).coerceAtLeast(0)
            val lookahead = if (stepPlans > 1) 1 else Design.STEP_LOOKAHEAD
            val passed = (targetIdx - cur) * stepDir < 0
            val stalled = now - stepLastChangeAt > Design.STEP_STALL_MS &&
                (stepIssued >= stepNeeded || inFlight >= lookahead)
            if (passed || stalled) {
                // Перелетели или плеер пропустил команду: считаем путь заново от текущей песни
                if (stepPlans >= 5) {
                    stepTarget = null
                    return
                }
                planSteps(cur, targetIdx)
                progress = 0
                inFlight = 0
            }

            val lookNow = if (stepPlans > 1) 1 else lookahead
            if (stepIssued < stepNeeded && inFlight < lookNow &&
                now - stepLastIssueAt >= Design.STEP_GAP_MS
            ) {
                if (stepDir > 0) tc.skipToNext() else tc.skipToPrevious()
                stepIssued++
                stepLastIssueAt = now
            }
            mainHandler.postDelayed(this, Design.STEP_TICK_MS)
        }
    }

    private fun stepToTrack(targetIdx: Int, from: Int) {
        val item = tracks.getOrNull(targetIdx) ?: return
        stepTarget = item
        stepLastCur = from
        stepPlans = 0
        stepLastIssueAt = 0L
        planSteps(from, targetIdx)
        mainHandler.removeCallbacks(stepRunnable)
        stepRunnable.run()
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
