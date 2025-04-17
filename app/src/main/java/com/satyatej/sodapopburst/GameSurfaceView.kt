package com.satyatej.sodapopburst

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor // Use alias to avoid clash with Compose Color
import android.graphics.Paint // Import Android Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue // Import for thread-safe queue
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlin.system.measureTimeMillis

// Assuming GameState is defined elsewhere (e.g., MainActivity or shared file)
// enum class GameState { PLAYING, GAME_OVER }

// Define a simple data class for tap events
data class TapEvent(val x: Float, val y: Float)

// --- Bottle Definition (for SurfaceView) ---
data class GameBottle(
    var id: Long = 0,
    var xPercent: Float = 0f, // *** Represents CENTER X percentage ***
    var yPx: Float = 0f,      // Position in pixels from top (CENTER Y)
    var color: Int = AndroidColor.TRANSPARENT, // Keep for tinting maybe? Or remove if using separate images
    var speedFactorPxPerSec: Float = 0f, // Store actual speed in pixels/sec
    var active: Boolean = false,
    var widthPx: Float = 0f, // Store size for collision/drawing
    var heightPx: Float = 0f // Store size for collision/drawing
) {
    // Reset function now takes necessary parameters including calculated sizes and speed
    fun reset(startXCenterPercent: Float, startYCenterPx: Float, startColorInt: Int, bottleW: Float, bottleH: Float, baseSpeedPx: Float) {
        id = System.nanoTime() // Simple ID
        xPercent = startXCenterPercent // Store center percentage
        yPx = startYCenterPx        // Store center Y coordinate
        color = startColorInt
        widthPx = bottleW
        heightPx = bottleH
        // Calculate and store actual speed for this bottle
        speedFactorPxPerSec = (Random.nextFloat() * 0.5f + 0.8f) * baseSpeedPx
        active = true
    }

    // Update position based on delta time, return false if off-screen (missed)
    fun update(deltaTimeSec: Float, viewHeight: Int): Boolean {
        if (!active) return true // Don't update inactive bottles
        yPx += speedFactorPxPerSec * deltaTimeSec // Update center Y
        // Check miss condition (TOP edge is off screen)
        if (yPx - heightPx / 2 > viewHeight) {
            active = false // Mark as inactive immediately
            return false
        }
        return true // Still active/visible
    }

    // Get the drawing rectangle for this bottle (calculated from CENTER)
    fun getRect(viewWidth: Int): RectF {
        val currentXPx = xPercent * viewWidth // Calculate center X in pixels
        return RectF(
            currentXPx - widthPx / 2, // Left edge
            yPx - heightPx / 2,        // Top edge
            currentXPx + widthPx / 2, // Right edge
            yPx + heightPx / 2         // Bottom edge
        )
    }
}


// --- GameSurfaceView Implementation ---
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val gameCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gameLoopJob: Job? = null

    // --- State Flows ---
    private val _scoreFlow = MutableStateFlow(0)
    val scoreFlow: StateFlow<Int> = _scoreFlow
    private val _gameStateFlow = MutableStateFlow(GameState.PLAYING)
    val gameStateFlow: StateFlow<GameState> = _gameStateFlow

    // --- Game State Variables ---
    private val activeBottles = mutableListOf<GameBottle>()
    private val bottlePool = mutableListOf<GameBottle>()
    @Volatile private var isSurfaceReady = false
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastSpawnTimeMs = 0L

    // --- Configuration ---
    private val targetFps = 60
    private val targetFrameTimeMillis = 1000L / targetFps
    private val baseSpeedDpPerSec = 100f
    private var baseSpeedPxPerSec = 0f
    private val bottleWidthDp = 40f
    private val bottleHeightDp = 80f
    private var bottleWidthPx = 0f
    private var bottleHeightPx = 0f
    private var initialBottleOffsetYPx = 0f
    private var spawnDelayMillis = 1000L
    @Volatile private var intensity = 1f

    // --- Drawing & Bitmaps ---
    private val bottlePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private var backgroundBitmap: Bitmap? = null
    // private var scaledBackgroundBitmap: Bitmap? = null // Replaced by manual calculation
    private var bottleBitmap: Bitmap? = null
    private var bottleSrcRect: Rect? = null

    // --- Sound Lambdas ---
    var playBurstSound: () -> Unit = { Log.w("GameSurfaceView", "playBurstSound not set") }
    var playMissSound: () -> Unit = { Log.w("GameSurfaceView", "playMissSound not set") }

    // --- Thread-Safe Tap Queue ---
    private val tapEventQueue = ConcurrentLinkedQueue<TapEvent>()

    init {
        holder.addCallback(this)
        isFocusable = true
        Log.d("GameSurfaceView", "Init")
        loadBitmaps()
        calculateDimensions(resources.displayMetrics.density)
    }

    private fun loadBitmaps() {
        try {
            backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.game_background)
            // *** Replace R.drawable.bottle with your actual bottle image resource ***
            bottleBitmap = BitmapFactory.decodeResource(resources, R.drawable.bottle) // Use placeholder
            if (bottleBitmap != null) {
                bottleSrcRect = Rect(0, 0, bottleBitmap!!.width, bottleBitmap!!.height)
            } else { Log.e("GameSurfaceView", "Failed to load bottle bitmap!") }
            if (backgroundBitmap == null) { Log.e("GameSurfaceView", "Failed to load background bitmap!") }
        } catch (e: Exception) { Log.e("GameSurfaceView", "Error loading bitmaps", e) }
    }

    private fun calculateDimensions(density: Float) {
        baseSpeedPxPerSec = baseSpeedDpPerSec * density
        bottleWidthPx = bottleWidthDp * density
        bottleHeightPx = bottleHeightDp * density
        initialBottleOffsetYPx = -bottleHeightPx / 2 // Start with center just above screen
        // No need to pre-scale background, will draw with crop logic
    }

    // Removed scaleBackgroundBitmap()

    // --- Game Loop Management (Unchanged) ---
    private fun startGameLoop() {
        if (gameLoopJob?.isActive == true) return
        if (!isSurfaceReady) return
        if (gameStateFlow.value == GameState.GAME_OVER) return

        gameLoopJob = gameCoroutineScope.launch {
            Log.i("GameSurfaceView", "Starting game loop...")
            var lastFrameTimeNanos = System.nanoTime()

            while (isActive && gameStateFlow.value == GameState.PLAYING) {
                if (!isSurfaceReady) break
                val currentTimeNanos = System.nanoTime()
                val deltaTimeNanos = (currentTimeNanos - lastFrameTimeNanos).coerceAtLeast(0)
                lastFrameTimeNanos = currentTimeNanos
                val deltaTimeSec = (deltaTimeNanos / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)

                updateGameState(deltaTimeSec)
                if (gameStateFlow.value != GameState.PLAYING) break
                drawGame(holder)

                val cycleTimeMillis = deltaTimeNanos / 1_000_000
                val sleepTimeMillis = targetFrameTimeMillis - cycleTimeMillis
                if (sleepTimeMillis > 0) { delay(sleepTimeMillis) } else { yield() }
            }
            Log.i("GameSurfaceView", "Game loop exited. Final State: ${gameStateFlow.value}")
        }
        gameLoopJob?.invokeOnCompletion { /* ... error handling ... */
            if (it != null && it !is CancellationException) {
                Log.e("GameSurfaceView", "Game loop crashed", it)
                _gameStateFlow.value = GameState.GAME_OVER
            } else { Log.i("GameSurfaceView", "Game loop completed or cancelled normally.") }
        }
    }

    private fun stopGameLoop() {
        if (gameLoopJob == null) return
        Log.i("GameSurfaceView", "Requesting game loop stop...")
        gameLoopJob?.cancel()
        gameLoopJob = null
    }

    // --- State Update (Unchanged logic, just formatting) ---
    private fun updateGameState(deltaTimeSec: Float) {
        if (viewHeight <= 0 || viewWidth <= 0) return
        processTapQueue()
        val iterator = activeBottles.listIterator()
        var missed = false
        while (iterator.hasNext()) {
            val bottle = iterator.next()
            if (!bottle.update(deltaTimeSec, viewHeight)) {
                iterator.remove(); releaseBottle(bottle); missed = true
            }
        }
        if (missed) { _gameStateFlow.value = GameState.GAME_OVER; playMissSound(); stopGameLoop(); return }
        spawnDelayMillis = max(150L, (1000 - intensity * 80).toLong())
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs - lastSpawnTimeMs > spawnDelayMillis) { spawnBottle(); lastSpawnTimeMs = currentTimeMs }
    }

    // --- Tap Processing (Unchanged) ---
    private fun processTapQueue() {
        while(true) {
            val tap = tapEventQueue.poll() ?: break
            var bottleTapped = false
            for (i in activeBottles.indices.reversed()) {
                if (i >= activeBottles.size) continue
                val bottle = activeBottles[i]
                val bottleRect = bottle.getRect(viewWidth)
                val expandedTapRect = RectF(bottleRect); expandedTapRect.inset(-bottle.widthPx * 0.2f, -bottle.heightPx * 0.2f)
                if (expandedTapRect.contains(tap.x, tap.y)) {
                    activeBottles.removeAt(i); releaseBottle(bottle); _scoreFlow.value += 1; playBurstSound(); bottleTapped = true; break
                }
            }
        }
    }

    // --- Spawning (Unchanged logic) ---
    private fun spawnBottle() {
        if (viewWidth <= 0) return
        val bottle = obtainBottle()
        val halfWidthPercent = (bottleWidthPx / 2f) / viewWidth
        val minSpawnCenterPercent = halfWidthPercent
        val maxSpawnCenterPercent = 1.0f - halfWidthPercent
        val spawnCenterPercent = if (maxSpawnCenterPercent >= minSpawnCenterPercent) Random.nextFloat().coerceIn(minSpawnCenterPercent, maxSpawnCenterPercent) else 0.5f
        val currentIntensitySpeed = baseSpeedPxPerSec * (1f + (intensity / 10f) * 2f)
        bottle.reset(spawnCenterPercent, initialBottleOffsetYPx, 0, bottleWidthPx, bottleHeightPx, currentIntensitySpeed)
        activeBottles.add(bottle)
    }

    // --- Drawing ---
    private fun drawGame(holder: SurfaceHolder) {
        val canvas: Canvas? = try { holder.lockCanvas() } catch (e: Exception) { null }
        if (canvas == null) return
        try {
            // 1. Draw Background with Crop Scale
            drawBackgroundCropped(canvas)

            // 2. Draw Bottles
            val currentBottleBitmap = bottleBitmap
            val srcRect = bottleSrcRect
            if (currentBottleBitmap != null && srcRect != null) {
                val bottleCount = activeBottles.size
                for (i in 0 until bottleCount) {
                    if (i >= activeBottles.size) break
                    val bottle = activeBottles[i]
                    val destRect = bottle.getRect(viewWidth)
                    canvas.drawBitmap(currentBottleBitmap, srcRect, destRect, bottlePaint)
                }
            } else {
                bottlePaint.style = Paint.Style.FILL
                for (bottle in activeBottles) {
                    val rect = bottle.getRect(viewWidth)
                    bottlePaint.color = AndroidColor.MAGENTA
                    canvas.drawRect(rect, bottlePaint)
                }
            }
        } finally {
            try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) { isSurfaceReady = false; stopGameLoop(); Log.e("GameSurfaceView", "Unlock error", e) }
        }
    }

    // --- Helper to draw background with aspect ratio crop ---
    private fun drawBackgroundCropped(canvas: Canvas) {
        if (backgroundBitmap == null) {
            canvas.drawColor(AndroidColor.DKGRAY) // Fallback color
            return
        }
        val bitmapWidth = backgroundBitmap!!.width
        val bitmapHeight = backgroundBitmap!!.height
        val canvasWidth = canvas.width
        val canvasHeight = canvas.height

        val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight
        val canvasRatio = canvasWidth.toFloat() / canvasHeight

        val src = Rect() // The portion of the bitmap to draw
        val dst = Rect(0, 0, canvasWidth, canvasHeight) // Where to draw on the canvas (full canvas)

        if (bitmapRatio > canvasRatio) { // Bitmap wider than canvas aspect ratio
            // Crop width, use full height
            val newBitmapWidth = (bitmapHeight * canvasRatio).toInt()
            val xOffset = (bitmapWidth - newBitmapWidth) / 2
            src.set(xOffset, 0, xOffset + newBitmapWidth, bitmapHeight)
        } else { // Bitmap taller than canvas aspect ratio (or same)
            // Crop height, use full width
            val newBitmapHeight = (bitmapWidth / canvasRatio).toInt()
            val yOffset = (bitmapHeight - newBitmapHeight) / 2
            src.set(0, yOffset, bitmapWidth, yOffset + newBitmapHeight)
        }

        canvas.drawBitmap(backgroundBitmap!!, src, dst, null) // Use null paint for simple bitmap draw
    }

    // --- Object Pooling (Unchanged) ---
    private fun obtainBottle(): GameBottle { return if (bottlePool.isNotEmpty()) bottlePool.removeAt(bottlePool.lastIndex) else GameBottle() }
    private fun releaseBottle(bottle: GameBottle) { bottle.active = false; bottlePool.add(bottle) }

    // --- Public Control Functions (Unchanged logic) ---
    fun handleTap(touchXPx: Float, touchYPx: Float) { if (gameStateFlow.value == GameState.PLAYING) tapEventQueue.offer(TapEvent(touchXPx, touchYPx)) }
    fun updateIntensity(newIntensity: Float) { this.intensity = newIntensity.coerceIn(0f, 10f) }
    fun resetGame() {
        gameCoroutineScope.launch {
            stopGameLoop()
            _scoreFlow.value = 0
            intensity = 1f
            activeBottles.forEach { releaseBottle(it) }
            activeBottles.clear()
            tapEventQueue.clear()
            _gameStateFlow.value = GameState.PLAYING
            lastSpawnTimeMs = System.currentTimeMillis()
            if (isSurfaceReady) startGameLoop() else Log.w("GameSurfaceView", "Reset game, but surface not ready.")
        }
    }
    fun cleanup() { stopGameLoop(); gameCoroutineScope.cancel() }

    // --- SurfaceHolder Callbacks (Unchanged) ---
    override fun surfaceCreated(holder: SurfaceHolder) { isSurfaceReady = true; if (_gameStateFlow.value == GameState.PLAYING) startGameLoop() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { viewWidth = width; viewHeight = height; calculateDimensions(resources.displayMetrics.density) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { isSurfaceReady = false; stopGameLoop() }
}
