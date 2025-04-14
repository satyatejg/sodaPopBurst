package com.satyatej.sodapopburst

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color as AndroidColor // Alias to avoid clash with Compose Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.ui.geometry.Offset // Keep for touch offset type if needed elsewhere
import androidx.compose.ui.geometry.Rect // Keep for rect type if needed elsewhere
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.*
import kotlin.coroutines.CoroutineContext
import kotlin.math.max
import kotlin.random.Random

// Re-define Bottle here or move to a separate file if preferred
// Using vars for pooling, added active flag
data class GameBottle(
    var id: Long = 0,
    var xPercent: Float = 0f, // Position as percentage of width
    var yPx: Float = 0f,      // Position in pixels from top
    var color: Int = AndroidColor.TRANSPARENT, // Use Android Color Int
    var speedFactor: Float = 1f,
    var active: Boolean = false
) {
    fun reset(startXPercent: Float, startYPx: Float, startColorInt: Int) {
        id = System.nanoTime() // Keep simple ID for now
        xPercent = startXPercent
        yPx = startYPx
        color = startColorInt
        speedFactor = Random.nextFloat() * 0.5f + 0.8f
        active = true
    }
}


class GameSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback, CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext = Dispatchers.Default + job // Use Default dispatcher for game loop

    private var gameThread: Job? = null
    private val bottlePaint = Paint().apply { isAntiAlias = true }
    private val backgroundPaint = Paint().apply { color = AndroidColor.DKGRAY } // Simple background

    // --- Game State (managed within the view for this example) ---
    private val activeBottles = mutableListOf<GameBottle>()
    private val bottlePool = mutableListOf<GameBottle>() // Object Pool
    private var lastSpawnTime = 0L
    private var viewWidth = 0
    private var viewHeight = 0
    private var bottleWidthPx = 80f // Example size, adjust as needed
    private var bottleHeightPx = 160f // Example size, adjust as needed
    private var baseSpeedPxPerSec = 120f // Example speed, adjust as needed
    private var initialBottleOffsetYPx = -160f // Example offset

    // Predefined Android colors
    private val androidSodaColors = listOf(
        AndroidColor.parseColor("#FFE53935"), // Red
        AndroidColor.parseColor("#FF43A047"), // Green
        AndroidColor.parseColor("#FF1E88E5"), // Blue
        AndroidColor.parseColor("#FFFDD835"), // Yellow
        AndroidColor.parseColor("#FF8E24AA"), // Purple
        AndroidColor.parseColor("#FFFF7043")  // Orange
    )

    // State Flows for communicating with Compose UI
    val scoreFlow = kotlinx.coroutines.flow.MutableStateFlow(0)
    val gameStateFlow = kotlinx.coroutines.flow.MutableStateFlow(GameState.PLAYING) // Assuming GameState enum exists
    var intensity = 1f // Can be updated from Compose if needed (e.g., via a function)

    // --- Touch Handling ---
    // Expose a function to be called from Compose's touch input
    fun handleTap(touchXPx: Float, touchYPx: Float) {
        if (gameStateFlow.value == GameState.PLAYING) {
            var bottleTapped = false
            // Iterate backwards for safe removal
            for (i in activeBottles.indices.reversed()) {
                val bottle = activeBottles[i]
                val bottleXPx = bottle.xPercent * viewWidth
                val bottleYPx = bottle.yPx

                // Simple Rect check for tap
                val tapCheckRect = RectF(
                    bottleXPx,
                    bottleYPx,
                    bottleXPx + bottleWidthPx,
                    bottleYPx + bottleHeightPx
                )
                // Expand tap area slightly for easier tapping
                val expandedTapRect = RectF(
                    tapCheckRect.left - bottleWidthPx * 0.2f,
                    tapCheckRect.top - bottleHeightPx * 0.2f,
                    tapCheckRect.right + bottleWidthPx * 0.2f,
                    tapCheckRect.bottom + bottleHeightPx * 0.2f
                )


                if (expandedTapRect.contains(touchXPx, touchYPx)) {
                    Log.i("GameSurfaceViewTap", ">>> SUCCESSFUL TAP on Bottle ID ${bottle.id}")
                    releaseBottle(bottle) // Return to pool
                    activeBottles.removeAt(i) // Remove from active list
                    scoreFlow.value += 1 // Update score via flow
                    // TODO: Play burst sound (needs SoundPool instance passed in or managed differently)
                    bottleTapped = true
                    break // Exit loop once a bottle is hit
                }
            }
            if (!bottleTapped) { Log.d("GameSurfaceViewTap", "--- Tap missed") }
        }
    }

    // --- Object Pooling Functions ---
    private fun obtainBottle(): GameBottle {
        return if (bottlePool.isNotEmpty()) {
            bottlePool.removeLast()
        } else {
            GameBottle()
        }
    }

    private fun releaseBottle(bottle: GameBottle) {
        bottle.active = false
        bottlePool.add(bottle)
    }

    init {
        holder.addCallback(this)
        isFocusable = true // Ensure view can receive focus/touch events if needed directly (though we'll use Compose input)
        Log.d("GameSurfaceView", "Init")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d("GameSurfaceView", "Surface Created")
        if (gameThread == null) {
            startGameLoop(holder)
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d("GameSurfaceView", "Surface Changed: w=$width, h=$height")
        viewWidth = width
        viewHeight = height
        // Update bottle sizes based on view size (example: make them relative)
        bottleWidthPx = width / 10f // Example: Bottle width is 1/10th of screen width
        bottleHeightPx = bottleWidthPx * 2 // Example: Bottle height is twice its width
        initialBottleOffsetYPx = -bottleHeightPx // Start fully off-screen
        baseSpeedPxPerSec = height / 5f // Example: Takes 5 seconds to cross the screen at base speed

        // Optionally restart loop if needed, but usually handled by surfaceCreated/Destroyed
        // stopGameLoop()
        // startGameLoop(holder)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d("GameSurfaceView", "Surface Destroyed")
        stopGameLoop()
    }

    fun resetGame() {
        Log.i("GameSurfaceView", "Resetting Game")
        scoreFlow.value = 0
        intensity = 1f // Reset intensity if controlled here
        // Clear active bottles and return them to the pool
        activeBottles.forEach { releaseBottle(it) }
        activeBottles.clear()
        gameStateFlow.value = GameState.PLAYING
        lastSpawnTime = System.currentTimeMillis() // Reset spawn timer
        Log.i("GameSurfaceView", "Game reset complete. Pool size: ${bottlePool.size}")
        // Ensure game loop restarts if it was stopped by game over
        if (gameThread == null || gameThread?.isActive == false) {
            startGameLoop(holder)
        }
    }

    private fun startGameLoop(holder: SurfaceHolder) {
        Log.i("GameSurfaceView", "Starting Game Loop")
        if (gameStateFlow.value == GameState.GAME_OVER) {
            Log.w("GameSurfaceView", "Attempted to start loop while game over, resetting.")
            resetGame() // Ensure we are in a playable state before starting
        }
        gameThread = launch { // Launch coroutine on the CoroutineScope's context (Default dispatcher)
            var lastFrameTimeNanos = System.nanoTime()

            while (isActive && gameStateFlow.value == GameState.PLAYING) {
                val nowNanos = System.nanoTime()
                val deltaTime = (nowNanos - lastFrameTimeNanos).coerceAtLeast(0) / 1_000_000_000.0f // Prevent negative delta on first frame/resets
                lastFrameTimeNanos = nowNanos

                // --- Update ---
                updateGame(deltaTime)

                // --- Draw ---
                var canvas: Canvas? = null
                try {
                    canvas = holder.lockCanvas()
                    if (canvas != null) {
                        synchronized(holder) { // Synchronize drawing
                            drawGame(canvas)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GameSurfaceView", "Error locking/drawing canvas", e)
                } finally {
                    if (canvas != null) {
                        try {
                            holder.unlockCanvasAndPost(canvas)
                        } catch (e: Exception) {
                            Log.e("GameSurfaceView", "Error unlocking canvas", e)
                        }
                    }
                }

                // --- Frame Limiting (Optional but recommended) ---
                // Simple busy-wait loop for more precise timing, but can consume CPU.
                // val targetFrameTimeNanos = 1_000_000_000 / 60 // Target 60 FPS
                // val timeSpentNanos = System.nanoTime() - nowNanos
                // val timeToWaitNanos = (targetFrameTimeNanos - timeSpentNanos).coerceAtLeast(0)
                // if (timeToWaitNanos > 0) {
                //     delay(timeToWaitNanos / 1_000_000) // Delay in milliseconds
                // }
                // Or simpler delay:
                delay(16) // Roughly targets 60 FPS, less precise

            }
            Log.i("GameSurfaceView", "Game Loop Ended. State: ${gameStateFlow.value}")
        } // End launch
    }

    private fun stopGameLoop() {
        Log.i("GameSurfaceView", "Stopping Game Loop")
        gameThread?.cancel() // Request cancellation of the coroutine
        gameThread = null
    }

    private fun updateGame(deltaTime: Float) {
        if (viewHeight <= 0 || viewWidth <= 0) return // Ensure view is ready

        // Check Game Over state first
        if (gameStateFlow.value != GameState.PLAYING) {
            return
        }

        val speedMultiplier = 1f + (intensity / 10f) * 4f
        val fallSpeedPx = baseSpeedPxPerSec * speedMultiplier * deltaTime

        // Update Bottle Positions using ListIterator for safe removal during iteration
        val iterator = activeBottles.listIterator()
        while (iterator.hasNext()) {
            val bottle = iterator.next()
            bottle.yPx += fallSpeedPx * bottle.speedFactor

            // Check miss condition
            if (bottle.yPx > viewHeight) {
                Log.d("GameSurfaceView", "Bottle ${bottle.id} missed")
                iterator.remove() // Remove from active list
                releaseBottle(bottle) // Return to pool
                // TODO: Play miss sound
                gameStateFlow.value = GameState.GAME_OVER // Update state via flow
                stopGameLoop() // Stop the loop on game over
                break // Exit update loop
            }
        }

        // Spawn New Bottles only if playing
        if (gameStateFlow.value == GameState.PLAYING) {
            val spawnDelayMillis = max(200L, (1500 - intensity * 120).toLong())
            if (System.currentTimeMillis() - lastSpawnTime > spawnDelayMillis) {
                val randomXPercent = Random.nextFloat()
                // Ensure xPercent stays within 0.0 to (1.0 - bottleWidth/viewWidth)
                val maxSpawnXPercent = 1.0f - (bottleWidthPx / viewWidth)
                val spawnXPercent = (randomXPercent * maxSpawnXPercent).coerceAtLeast(0f)

                val bottle = obtainBottle()
                bottle.reset(
                    startXPercent = spawnXPercent,
                    startYPx = initialBottleOffsetYPx,
                    startColorInt = androidSodaColors.random()
                )
                activeBottles.add(bottle)
                lastSpawnTime = System.currentTimeMillis()
            }
        }
    }

    private fun drawGame(canvas: Canvas) {
        // 1. Clear background
        canvas.drawColor(AndroidColor.BLACK, PorterDuff.Mode.CLEAR) // Clear previous frame
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), backgroundPaint) // Draw background color

        // 2. Draw Bottles
        val bottleCount = activeBottles.size
        for (i in 0 until bottleCount) {
            // Check index validity in case of concurrent modification (less likely with synchronized block)
            if (i >= activeBottles.size) break
            val bottle = activeBottles[i]

            val drawX = bottle.xPercent * viewWidth
            val drawY = bottle.yPx

            // Simple visibility check (drawing items off-screen is generally okay, but can cull if needed)
            // if (drawY < viewHeight + bottleHeightPx && drawY > -bottleHeightPx * 2) {
            bottlePaint.color = bottle.color
            canvas.drawRect(
                drawX,
                drawY,
                drawX + bottleWidthPx,
                drawY + bottleHeightPx,
                bottlePaint
            )
            // }
        }
        // Log.d("GameSurfaceView", "Drew $bottleCount bottles")
    }

    // Optional: Call this when the Activity/Fragment is destroyed to clean up the coroutine scope
    fun cleanup() {
        Log.d("GameSurfaceView", "Cleaning up CoroutineScope")
        job.cancel() // Cancel the scope's job, cancelling the game loop if running
    }
}

// Assuming GameState enum exists from MainActivity or is moved to a shared file
// enum class GameState { PLAYING, GAME_OVER }
