package com.satyatej.sodapopburst

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor // Use alias to avoid clash with Compose Color
import android.graphics.Paint // Import Android Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.random.Random
import kotlin.system.measureTimeMillis

// Assuming GameState is defined elsewhere (e.g., MainActivity or shared file)
// enum class GameState { PLAYING, GAME_OVER }

// --- Bottle Definition (for SurfaceView) ---
data class GameBottle(
    var id: Long = 0,
    var xPercent: Float = 0f, // Position as percentage of width
    var yPx: Float = 0f,      // Position in pixels from top
    var color: Int = AndroidColor.TRANSPARENT, // Use Android Color Int
    var speedFactorPxPerSec: Float = 0f, // Store actual speed in pixels/sec
    var active: Boolean = false,
    var widthPx: Float = 0f, // Store size for collision/drawing
    var heightPx: Float = 0f // Store size for collision/drawing
) {
    // Reset function now takes necessary parameters including calculated sizes and speed
    fun reset(startXPercent: Float, startYPx: Float, startColorInt: Int, bottleW: Float, bottleH: Float, baseSpeedPx: Float) {
        id = System.nanoTime() // Simple ID
        xPercent = startXPercent
        yPx = startYPx
        color = startColorInt
        widthPx = bottleW
        heightPx = bottleH
        // Calculate and store actual speed for this bottle
        speedFactorPxPerSec = (Random.nextFloat() * 0.5f + 0.8f) * baseSpeedPx
        active = true
        // Log.d("GameBottle", "Resetting Bottle $id: y=$startYPx, speed=${speedFactorPxPerSec}px/s (base=$baseSpeedPx)")
    }

    // Update position based on delta time, return false if off-screen (missed)
    fun update(deltaTimeSec: Float, viewHeight: Int): Boolean {
        if (!active) return true // Don't update inactive bottles
        yPx += speedFactorPxPerSec * deltaTimeSec
        // Check miss condition (top edge is off screen - using top edge yPx - heightPx/2)
        if (yPx - heightPx / 2 > viewHeight) {
            active = false // Mark as inactive immediately
            return false
        }
        return true // Still active/visible
    }

    // Get the drawing rectangle for this bottle (centered)
    fun getRect(viewWidth: Int): RectF {
        val currentXPx = xPercent * viewWidth + widthPx / 2 // Adjust X to be centered based on %
        return RectF(
            currentXPx - widthPx / 2,
            yPx - heightPx / 2,
            currentXPx + widthPx / 2,
            yPx + heightPx / 2
        )
    }
}


// --- GameSurfaceView Implementation ---
class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    // Coroutine scope tied to the Default dispatcher for background work
    private val gameCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gameLoopJob: Job? = null // The Job for the main game loop

    // --- State Flows for UI Communication ---
    private val _scoreFlow = MutableStateFlow(0)
    val scoreFlow: StateFlow<Int> = _scoreFlow

    private val _gameStateFlow = MutableStateFlow(GameState.PLAYING) // Start in PLAYING state
    val gameStateFlow: StateFlow<GameState> = _gameStateFlow

    // --- Game State Variables ---
    private val activeBottles = mutableListOf<GameBottle>()
    private val bottlePool = mutableListOf<GameBottle>() // Object Pool
    @Volatile private var isSurfaceReady = false
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastSpawnTimeMs = 0L

    // --- Configuration ---
    private val targetFps = 60 // Target frame rate
    private val targetFrameTimeMillis = 1000L / targetFps
    private val baseSpeedDpPerSec = 100f // Base speed in DP/s
    private var baseSpeedPxPerSec = 0f
    private val bottleWidthDp = 40f
    private val bottleHeightDp = 80f
    private var bottleWidthPx = 0f
    private var bottleHeightPx = 0f
    private var initialBottleOffsetYPx = 0f
    private var spawnDelayMillis = 1000L // Initial spawn delay
    @Volatile private var intensity = 1f // Game intensity affecting speed/spawn - volatile for thread safety

    // --- Drawing ---
    private val bottlePaint = Paint().apply { isAntiAlias = true }
    private val backgroundPaint = Paint().apply { color = AndroidColor.DKGRAY } // Default background

    // --- Colors ---
    private val androidSodaColors = listOf(
        AndroidColor.parseColor("#FFE53935"), AndroidColor.parseColor("#FF43A047"),
        AndroidColor.parseColor("#FF1E88E5"), AndroidColor.parseColor("#FFFDD835"),
        AndroidColor.parseColor("#FF8E24AA"), AndroidColor.parseColor("#FFFF7043")
    )

    // --- Lambdas for Sound (to be set from outside) ---
    var playBurstSound: () -> Unit = { Log.w("GameSurfaceView", "playBurstSound not set") }
    var playMissSound: () -> Unit = { Log.w("GameSurfaceView", "playMissSound not set") }

    init {
        holder.addCallback(this)
        isFocusable = true
        Log.d("GameSurfaceView", "Init")
        // Calculate initial pixel values based on density
        calculateDimensions(resources.displayMetrics.density)
    }

    private fun calculateDimensions(density: Float) {
        baseSpeedPxPerSec = baseSpeedDpPerSec * density
        bottleWidthPx = bottleWidthDp * density
        bottleHeightPx = bottleHeightDp * density
        initialBottleOffsetYPx = -bottleHeightPx // Start with top edge just off screen
        Log.d("GameSurfaceView", "Calculated Dims: BaseSpeed=${baseSpeedPxPerSec}px/s, BottleW=${bottleWidthPx}px, BottleH=${bottleHeightPx}px")

    }

    // --- Game Loop Management ---
    private fun startGameLoop() {
        if (gameLoopJob?.isActive == true) {
            Log.w("GameSurfaceView", "Attempted to start loop, but already running.")
            return
        }
        if (!isSurfaceReady) {
            Log.w("GameSurfaceView", "Surface not ready, cannot start loop.")
            return
        }
        if (gameStateFlow.value == GameState.GAME_OVER) {
            Log.w("GameSurfaceView", "Attempting to start loop but game is over. Reset first.")
            return // Must call resetGame() externally before starting
        }


        gameLoopJob = gameCoroutineScope.launch {
            Log.i("GameSurfaceView", "Starting game loop...")
            var lastFrameTimeNanos = System.nanoTime() // Use var here

            while (isActive && gameStateFlow.value == GameState.PLAYING) { // Check state each iteration
                if (!isSurfaceReady) {
                    Log.w("GameSurfaceView", "Surface became invalid during loop.")
                    break // Exit loop
                }

                val currentTimeNanos = System.nanoTime()
                // Prevent negative delta on first frame after resume/reset
                val deltaTimeNanos = (currentTimeNanos - lastFrameTimeNanos).coerceAtLeast(0)
                lastFrameTimeNanos = currentTimeNanos // lastFrameTimeNanos is updated, so it must be var
                // Clamp delta time to prevent extreme jumps if system lags
                val deltaTimeSec = (deltaTimeNanos / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)

                // --- Update ---
                updateGameState(deltaTimeSec)

                // Check again if game ended during update
                if (gameStateFlow.value != GameState.PLAYING) break

                // --- Draw ---
                val drawTimeMillis = measureTimeMillis { // drawTimeMillis is val, not reassigned
                    drawGame(holder) // Pass holder explicitly
                }

                // --- Frame Pacing ---
                val cycleTimeMillis = deltaTimeNanos / 1_000_000 // val, not reassigned
                val sleepTimeMillis = targetFrameTimeMillis - cycleTimeMillis // val, not reassigned
                // Log.d("GameSurfaceView", "Cycle: ${cycleTimeMillis}ms, Draw: ${drawTimeMillis}ms, Sleep: ${sleepTimeMillis}ms")

                if (sleepTimeMillis > 0) {
                    delay(sleepTimeMillis)
                } else {
                    // Barely missed target or no time to sleep, yield immediately
                    yield()
                }
            }
            Log.i("GameSurfaceView", "Game loop exited. Final State: ${gameStateFlow.value}")
        }
        gameLoopJob?.invokeOnCompletion { throwable ->
            if (throwable != null && throwable !is CancellationException) {
                Log.e("GameSurfaceView", "Game loop crashed", throwable)
                // Potentially set game state to error or try to recover
                _gameStateFlow.value = GameState.GAME_OVER // Go to game over on crash
            } else {
                Log.i("GameSurfaceView", "Game loop completed or cancelled normally.")
            }
        }
    }

    private fun stopGameLoop() {
        if (gameLoopJob == null) return // Already stopped
        Log.i("GameSurfaceView", "Requesting game loop stop...")
        gameLoopJob?.cancel() // Request cancellation
        gameLoopJob = null
    }

    // --- State Update ---
    private fun updateGameState(deltaTimeSec: Float) {
        if (viewHeight <= 0 || viewWidth <= 0) return // View not measured yet

        // Update existing bottles
        val iterator = activeBottles.listIterator()
        var missed = false // Use var
        while (iterator.hasNext()) {
            val bottle = iterator.next()
            if (!bottle.update(deltaTimeSec, viewHeight)) {
                // Bottle missed (went off screen)
                Log.d("GameSurfaceView", "Bottle ${bottle.id} Missed!")
                iterator.remove()
                releaseBottle(bottle)
                missed = true // Reassign var
            }
        }

        if (missed) { // Only trigger game over if a bottle was missed *this frame*
            Log.w("GameSurfaceView", "Game Over triggered by missed bottle.")
            _gameStateFlow.value = GameState.GAME_OVER // Update StateFlow
            playMissSound() // Play sound via lambda
            stopGameLoop() // Stop the game loop
            return // Don't spawn new bottles if game just ended
        }

        // Spawn New Bottles if still playing
        spawnDelayMillis = max(150L, (1000 - intensity * 80).toLong()) // Dynamic spawn rate
        val currentTimeMs = System.currentTimeMillis()
        if (currentTimeMs - lastSpawnTimeMs > spawnDelayMillis) {
            spawnBottle()
            lastSpawnTimeMs = currentTimeMs // lastSpawnTimeMs is var
        }
    }

    private fun spawnBottle() {
        if (viewWidth <= 0) return // Cannot calculate position

        val bottle = obtainBottle()
        val effectiveBottleWidth = bottleWidthPx

        // Ensure xPercent keeps the entire bottle on screen horizontally (center point based)
        val halfWidthPercent = (effectiveBottleWidth / 2f) / viewWidth
        val maxSpawnXPercent = (1.0f - halfWidthPercent)
        val minSpawnXPercent = halfWidthPercent
        val spawnXPercent = Random.nextFloat().coerceIn(minSpawnXPercent, maxSpawnXPercent)


        val currentIntensitySpeed = baseSpeedPxPerSec * (1f + (intensity / 10f) * 2f)

        bottle.reset(
            startXPercent = spawnXPercent,
            startYPx = initialBottleOffsetYPx + bottleHeightPx / 2, // Start position (center Y)
            startColorInt = androidSodaColors.random(),
            bottleW = effectiveBottleWidth,
            bottleH = bottleHeightPx,
            baseSpeedPx = currentIntensitySpeed // Pass current speed calculation
        )
        activeBottles.add(bottle)
    }

    // --- Drawing ---
    private fun drawGame(holder: SurfaceHolder) { // Receive holder
        val canvas: Canvas? = try {
            holder.lockCanvas() // Lock canvas on the holder
        } catch (e: IllegalArgumentException){
            Log.e("GameSurfaceView", "Failed to lock canvas. Surface might be invalid.", e)
            null
        } catch (e: Exception) {
            Log.e("GameSurfaceView", "Unknown error locking canvas", e)
            null
        }

        if (canvas == null) {
            if (isSurfaceReady) Log.w("GameSurfaceView", "Canvas lock returned null unexpectedly.")
            return // Couldn't get canvas, skip drawing
        }

        try {
            // 1. Clear background
            canvas.drawColor(AndroidColor.TRANSPARENT, PorterDuff.Mode.CLEAR)
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), backgroundPaint)

            // 2. Draw Bottles
            val bottleCount = activeBottles.size
            for (i in 0 until bottleCount) {
                // Check index just in case (belt and suspenders)
                if (i >= activeBottles.size) break
                val bottle = activeBottles[i]
                val rect = bottle.getRect(viewWidth) // Get bottle bounds (centered)

                // Simple culling check (optional)
                if (RectF.intersects(rect, RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat()))) {
                    bottlePaint.color = bottle.color
                    canvas.drawRect(rect, bottlePaint)
                }
            }
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas) // Unlock on the same holder
            } catch (e: IllegalStateException){
                Log.e("GameSurfaceView", "Failed to unlock/post canvas. Surface might have been released.", e)
                // Surface is likely invalid now, ensure loop stops if running
                isSurfaceReady = false
                stopGameLoop()
            } catch (e: Exception) {
                Log.e("GameSurfaceView", "Unknown error unlocking/posting canvas", e)
            }
        }
    }

    // --- Object Pooling ---
    private fun obtainBottle(): GameBottle {
        // Fixed: Use removeAt(lastIndex) for API level compatibility
        return if (bottlePool.isNotEmpty()) bottlePool.removeAt(bottlePool.lastIndex) else GameBottle()
    }

    private fun releaseBottle(bottle: GameBottle) {
        bottle.active = false
        bottlePool.add(bottle)
    }

    // --- Public Control Functions ---
    fun handleTap(touchXPx: Float, touchYPx: Float) {
        if (gameStateFlow.value != GameState.PLAYING) return

        gameCoroutineScope.launch {
            var bottleTapped = false
            for (i in activeBottles.indices.reversed()) {
                if (i >= activeBottles.size) continue
                val bottle = activeBottles[i]
                val bottleRect = bottle.getRect(viewWidth)

                // Slightly expand the tap area
                val expandedTapRect = RectF(bottleRect)
                expandedTapRect.inset(-bottle.widthPx * 0.2f, -bottle.heightPx * 0.2f)

                if (expandedTapRect.contains(touchXPx, touchYPx)) {
                    Log.i("GameSurfaceViewTap", ">>> TAP on Bottle ID ${bottle.id}")
                    activeBottles.removeAt(i)
                    releaseBottle(bottle)
                    _scoreFlow.value += 1
                    playBurstSound()
                    bottleTapped = true
                    break
                }
            }
        }
    }

    fun updateIntensity(newIntensity: Float) {
        this.intensity = newIntensity.coerceIn(0f, 10f)
    }

    fun resetGame() {
        gameCoroutineScope.launch {
            Log.i("GameSurfaceView", "Resetting Game State...")
            stopGameLoop()
            _scoreFlow.value = 0
            intensity = 1f
            activeBottles.forEach { releaseBottle(it) }
            activeBottles.clear()
            _gameStateFlow.value = GameState.PLAYING
            lastSpawnTimeMs = System.currentTimeMillis()
            Log.i("GameSurfaceView", "Game reset complete. Pool size: ${bottlePool.size}")
            if (isSurfaceReady) {
                startGameLoop()
            } else {
                Log.w("GameSurfaceView", "Reset game, but surface not ready to restart loop.")
            }
        }
    }

    fun cleanup() {
        Log.d("GameSurfaceView", "Cleaning up GameSurfaceView scope and loop")
        stopGameLoop()
        gameCoroutineScope.cancel()
    }

    // --- SurfaceHolder Callbacks ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i("GameSurfaceView", "Surface Created")
        isSurfaceReady = true
        if (_gameStateFlow.value == GameState.PLAYING) {
            startGameLoop()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i("GameSurfaceView", "Surface Changed: w=$width, h=$height")
        val oldWidth = viewWidth
        val oldHeight = viewHeight
        viewWidth = width
        viewHeight = height
        if (width != oldWidth || height != oldHeight) {
            calculateDimensions(resources.displayMetrics.density)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i("GameSurfaceView", "Surface Destroyed")
        isSurfaceReady = false
        stopGameLoop()
    }
}

