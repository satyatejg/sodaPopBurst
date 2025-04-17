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
import kotlin.math.min // Import min
import kotlin.random.Random
import kotlin.system.measureTimeMillis

// GameState defined in MainActivity

// Define a simple data class for tap events
data class TapEvent(val x: Float, val y: Float)

// --- Data Classes ---
data class GameBottle( /* ... existing GameBottle code ... */
    var id: Long = 0,
    var xPercent: Float = 0f, // Represents CENTER X percentage
    var yPx: Float = 0f,      // Position in pixels from top (CENTER Y)
    var color: Int = AndroidColor.TRANSPARENT,
    var speedFactorPxPerSec: Float = 0f,
    var active: Boolean = false,
    var widthPx: Float = 0f,
    var heightPx: Float = 0f
) {
    fun reset(startXCenterPercent: Float, startYCenterPx: Float, startColorInt: Int, bottleW: Float, bottleH: Float, baseSpeedPx: Float) {
        id = System.nanoTime(); xPercent = startXCenterPercent; yPx = startYCenterPx; color = startColorInt; widthPx = bottleW; heightPx = bottleH
        speedFactorPxPerSec = (Random.nextFloat() * 0.5f + 0.8f) * baseSpeedPx; active = true
    }
    fun update(deltaTimeSec: Float, viewHeight: Int): Boolean {
        if (!active) return true; yPx += speedFactorPxPerSec * deltaTimeSec
        if (yPx - heightPx / 2 > viewHeight) { active = false; return false }; return true
    }
    fun getRect(viewWidth: Int): RectF {
        val currentXPx = xPercent * viewWidth; return RectF(currentXPx - widthPx / 2, yPx - heightPx / 2, currentXPx + widthPx / 2, yPx + heightPx / 2)
    }
}

// New data class for Burst Animations
data class BurstAnimation(
    var id: Long = 0, // Optional: for tracking if needed
    var xPx: Float = 0f, // Center X position
    var yPx: Float = 0f, // Center Y position
    var startTimeMs: Long = 0L,
    var active: Boolean = false
) {
    val durationMs = 300L // How long the burst animation lasts (e.g., 300ms)

    fun reset(px: Float, py: Float) {
        id = System.nanoTime()
        xPx = px
        yPx = py
        startTimeMs = System.currentTimeMillis()
        active = true
    }

    // Check if the animation has finished
    fun hasFinished(currentTimeMs: Long): Boolean {
        return currentTimeMs - startTimeMs > durationMs
    }

    // Get current alpha (0-255) for fade out effect
    fun getCurrentAlpha(currentTimeMs: Long): Int {
        val elapsedTime = currentTimeMs - startTimeMs
        if (elapsedTime >= durationMs) return 0
        // Linear fade out
        return (255 * (1.0f - elapsedTime.toFloat() / durationMs)).toInt().coerceIn(0, 255)
    }
}

// --- GameSurfaceView Implementation ---
class GameSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    private val gameCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gameLoopJob: Job? = null

    // --- State Flows ---
    private val _scoreFlow = MutableStateFlow(0); val scoreFlow: StateFlow<Int> = _scoreFlow
    private val _gameStateFlow = MutableStateFlow(GameState.PLAYING); val gameStateFlow: StateFlow<GameState> = _gameStateFlow

    // --- Game State Variables ---
    private val activeBottles = mutableListOf<GameBottle>()
    private val bottlePool = mutableListOf<GameBottle>()
    private val activeBursts = mutableListOf<BurstAnimation>() // List for active bursts
    private val burstPool = mutableListOf<BurstAnimation>()   // Pool for burst animations
    @Volatile private var isSurfaceReady = false
    private var viewWidth = 0; private var viewHeight = 0; private var lastSpawnTimeMs = 0L

    // --- Configuration ---
    private val targetFps = 60; private val targetFrameTimeMillis = 1000L / targetFps
    private val baseSpeedDpPerSec = 100f; private var baseSpeedPxPerSec = 0f
    private val bottleWidthDp = 40f; private val bottleHeightDp = 80f
    private var bottleWidthPx = 0f; private var bottleHeightPx = 0f
    private var initialBottleOffsetYPx = 0f; private var spawnDelayMillis = 1000L
    @Volatile private var intensity = 1f

    // --- Drawing & Bitmaps ---
    private val bottlePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val burstPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true } // Paint for bursts (for alpha)
    private var backgroundBitmap: Bitmap? = null
    private var bottleBitmap: Bitmap? = null; private var bottleSrcRect: Rect? = null
    private var splashBitmap: Bitmap? = null // Bitmap for the splash effect

    // --- Sound Lambdas ---
    var playBurstSound: () -> Unit = {}; var playMissSound: () -> Unit = {}

    // --- Thread-Safe Tap Queue ---
    private val tapEventQueue = ConcurrentLinkedQueue<TapEvent>()

    init {
        holder.addCallback(this); isFocusable = true; Log.d("GameSurfaceView", "Init")
        loadBitmaps(); calculateDimensions(resources.displayMetrics.density)
    }

    private fun loadBitmaps() {
        try {
            backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.game_background)
            // *** Replace with your actual bottle drawable ***
            bottleBitmap = BitmapFactory.decodeResource(resources, R.drawable.bottle) // Use placeholder
            // *** Replace with your actual splash drawable ***
            splashBitmap = BitmapFactory.decodeResource(resources, R.drawable.splash) // Placeholder!

            if (bottleBitmap != null) bottleSrcRect = Rect(0, 0, bottleBitmap!!.width, bottleBitmap!!.height)
            else Log.e("GameSurfaceView", "Failed to load bottle bitmap!")
            if (splashBitmap == null) Log.e("GameSurfaceView", "Failed to load splash bitmap!")
            if (backgroundBitmap == null) Log.e("GameSurfaceView", "Failed to load background bitmap!")
        } catch (e: Exception) { Log.e("GameSurfaceView", "Error loading bitmaps", e) }
    }

    private fun calculateDimensions(density: Float) {
        baseSpeedPxPerSec = baseSpeedDpPerSec * density
        bottleWidthPx = bottleWidthDp * density; bottleHeightPx = bottleHeightDp * density
        initialBottleOffsetYPx = -bottleHeightPx / 2 // Center Y starts just above screen
        // Background is scaled via drawBackgroundCropped
    }

   // Removed scaleBackgroundBitmap()

    // --- Game Loop Management ---
     private fun startGameLoop() {
         if (gameLoopJob?.isActive == true) return; if (!isSurfaceReady) return; if (gameStateFlow.value == GameState.GAME_OVER) return
         gameLoopJob = gameCoroutineScope.launch {
             Log.i("GameSurfaceView", "Starting game loop..."); var lastFrameTimeNanos = System.nanoTime()
             while (isActive && gameStateFlow.value == GameState.PLAYING) {
                 if (!isSurfaceReady) break; val currentTimeNanos = System.nanoTime()
                 val deltaTimeNanos = (currentTimeNanos - lastFrameTimeNanos).coerceAtLeast(0); lastFrameTimeNanos = currentTimeNanos
                 val deltaTimeSec = (deltaTimeNanos / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)
                 updateGameState(deltaTimeSec); if (gameStateFlow.value != GameState.PLAYING) break; drawGame(holder)
                 val cycleTimeMillis = deltaTimeNanos / 1_000_000; val sleepTimeMillis = targetFrameTimeMillis - cycleTimeMillis
                 if (sleepTimeMillis > 0) { delay(sleepTimeMillis) } else { yield() }
             }
             Log.i("GameSurfaceView", "Game loop exited. Final State: ${gameStateFlow.value}")
         }
         gameLoopJob?.invokeOnCompletion { if (it != null && it !is CancellationException) { Log.e("GameSurfaceView", "Loop crashed", it); _gameStateFlow.value = GameState.GAME_OVER } else { Log.i("GameSurfaceView", "Loop stopped normally.") } }
     }
     private fun stopGameLoop() { if (gameLoopJob == null) return; Log.i("GameSurfaceView", "Requesting loop stop..."); gameLoopJob?.cancel(); gameLoopJob = null }

    // --- State Update ---
    private fun updateGameState(deltaTimeSec: Float) {
        if (viewHeight <= 0 || viewWidth <= 0) return
        processTapQueue() // Process taps first

        // Update Bottles
        val bottleIterator = activeBottles.listIterator()
        var missed = false
        while (bottleIterator.hasNext()) {
            val bottle = bottleIterator.next()
            if (!bottle.update(deltaTimeSec, viewHeight)) {
                bottleIterator.remove(); releaseBottle(bottle); missed = true
            }
        }

        // Update Burst Animations
        val burstIterator = activeBursts.listIterator()
        val currentTimeMs = System.currentTimeMillis()
        while (burstIterator.hasNext()) {
            val burst = burstIterator.next()
            if (burst.hasFinished(currentTimeMs)) {
                burstIterator.remove()
                releaseBurst(burst)
            }
        }

        if (missed) { _gameStateFlow.value = GameState.GAME_OVER; playMissSound(); stopGameLoop(); return }

        // Spawn Bottles
        spawnDelayMillis = max(150L, (1000 - intensity * 80).toLong())
        if (currentTimeMs - lastSpawnTimeMs > spawnDelayMillis) { spawnBottle(); lastSpawnTimeMs = currentTimeMs }
    }

    // --- Tap Processing ---
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
                     triggerBurstAnimation(bottle.xPercent * viewWidth + bottle.widthPx / 2, bottle.yPx) // Use bottle center for burst
                    activeBottles.removeAt(i); releaseBottle(bottle)
                    _scoreFlow.value += 1; playBurstSound()
                    bottleTapped = true; break
                }
            }
        }
    }

     // --- Trigger Burst Animation ---
     private fun triggerBurstAnimation(xPx: Float, yPx: Float) {
         val burst = obtainBurst()
         burst.reset(xPx, yPx)
         activeBursts.add(burst)
         // Log.d("GameSurfaceView", "Triggered burst at ($xPx, $yPx). Active bursts: ${activeBursts.size}")
     }

    // --- Spawning ---
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
             // 1. Draw Background
             drawBackgroundCropped(canvas)

             // 2. Draw Bottles
             val currentBottleBitmap = bottleBitmap; val srcRect = bottleSrcRect
             if (currentBottleBitmap != null && srcRect != null) {
                 val bottleCount = activeBottles.size
                 for (i in 0 until bottleCount) {
                     if (i >= activeBottles.size) break; val bottle = activeBottles[i]
                     val destRect = bottle.getRect(viewWidth)
                     canvas.drawBitmap(currentBottleBitmap, srcRect, destRect, bottlePaint)
                 }
             } else { /* ... fallback drawing ... */
                 bottlePaint.style = Paint.Style.FILL
                 for (bottle in activeBottles) { val rect = bottle.getRect(viewWidth); bottlePaint.color = AndroidColor.MAGENTA; canvas.drawRect(rect, bottlePaint) }
             }

             // 3. Draw Burst Animations
             val currentSplashBitmap = splashBitmap
             if (currentSplashBitmap != null) {
                  val currentTimeMs = System.currentTimeMillis()
                  val splashSrcRect = Rect(0, 0, currentSplashBitmap.width, currentSplashBitmap.height) // Source rect for splash
                  for (i in activeBursts.indices.reversed()) {
                      if (i >= activeBursts.size) continue
                      val burst = activeBursts[i]
                      val alpha = burst.getCurrentAlpha(currentTimeMs)
                      if (alpha > 0) {
                           burstPaint.alpha = alpha
                           // Adjust burst size as needed (e.g., make it larger than bottle)
                           val burstWidth = bottleWidthPx * 1.5f
                           val burstHeight = bottleHeightPx * 1.5f
                           val burstDestRect = RectF(
                               burst.xPx - burstWidth / 2,
                               burst.yPx - burstHeight / 2,
                               burst.xPx + burstWidth / 2,
                               burst.yPx + burstHeight / 2
                           )
                           canvas.drawBitmap(currentSplashBitmap, splashSrcRect, burstDestRect, burstPaint)
                      }
                  }
             }

         } finally {
             try { holder.unlockCanvasAndPost(canvas) } catch (e: Exception) { isSurfaceReady = false; stopGameLoop(); Log.e("GameSurfaceView", "Unlock error", e) }
         }
     }

    // --- Helper to draw background with aspect ratio crop ---
     private fun drawBackgroundCropped(canvas: Canvas) {
         if (backgroundBitmap == null) { canvas.drawColor(AndroidColor.DKGRAY); return }
         val bitmapWidth = backgroundBitmap!!.width; val bitmapHeight = backgroundBitmap!!.height
         val canvasWidth = canvas.width; val canvasHeight = canvas.height
         val bitmapRatio = bitmapWidth.toFloat() / bitmapHeight; val canvasRatio = canvasWidth.toFloat() / canvasHeight
         val src = Rect(); val dst = Rect(0, 0, canvasWidth, canvasHeight)
         if (bitmapRatio > canvasRatio) { val newBitmapWidth = (bitmapHeight * canvasRatio).toInt(); val xOffset = (bitmapWidth - newBitmapWidth) / 2; src.set(xOffset, 0, xOffset + newBitmapWidth, bitmapHeight) }
         else { val newBitmapHeight = (bitmapWidth / canvasRatio).toInt(); val yOffset = (bitmapHeight - newBitmapHeight) / 2; src.set(0, yOffset, bitmapWidth, yOffset + newBitmapHeight) }
         canvas.drawBitmap(backgroundBitmap!!, src, dst, null)
     }

    // --- Object Pooling ---
    private fun obtainBottle(): GameBottle { return if (bottlePool.isNotEmpty()) bottlePool.removeAt(bottlePool.lastIndex) else GameBottle() }
    private fun releaseBottle(bottle: GameBottle) { bottle.active = false; bottlePool.add(bottle) }
    // Burst Pooling
    private fun obtainBurst(): BurstAnimation { return if (burstPool.isNotEmpty()) burstPool.removeAt(burstPool.lastIndex) else BurstAnimation() }
    private fun releaseBurst(burst: BurstAnimation) { burst.active = false; burstPool.add(burst) }

    // --- Public Control Functions ---
    fun handleTap(touchXPx: Float, touchYPx: Float) { if (gameStateFlow.value == GameState.PLAYING) tapEventQueue.offer(TapEvent(touchXPx, touchYPx)) }
    fun updateIntensity(newIntensity: Float) { this.intensity = newIntensity.coerceIn(0f, 10f) }
    fun resetGame() {
        gameCoroutineScope.launch {
            stopGameLoop()
            _scoreFlow.value = 0; intensity = 1f
            activeBottles.forEach { releaseBottle(it) }; activeBottles.clear()
            activeBursts.forEach { releaseBurst(it) }; activeBursts.clear()
            tapEventQueue.clear()
            _gameStateFlow.value = GameState.PLAYING
            lastSpawnTimeMs = System.currentTimeMillis()
            if (isSurfaceReady) startGameLoop() else Log.w("GameSurfaceView", "Reset game, but surface not ready.")
        }
    }
    fun cleanup() { stopGameLoop(); gameCoroutineScope.cancel() }

    // --- SurfaceHolder Callbacks ---
    override fun surfaceCreated(holder: SurfaceHolder) { isSurfaceReady = true; if (_gameStateFlow.value == GameState.PLAYING) startGameLoop() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) { viewWidth = width; viewHeight = height; calculateDimensions(resources.displayMetrics.density) }
    override fun surfaceDestroyed(holder: SurfaceHolder) { isSurfaceReady = false; stopGameLoop() }
}
