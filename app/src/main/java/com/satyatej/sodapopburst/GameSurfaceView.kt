package com.satyatej.sodapopburst

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color as AndroidColor // Alias to avoid Compose clash
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.MotionEvent // Import for onTouchEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentLinkedQueue // For thread-safe taps
import kotlin.collections.ArrayDeque // For efficient object pools
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt // <<<--- ADD THIS IMPORT ---<<<
import kotlin.random.Random
import kotlin.system.measureTimeMillis

// Assuming GameState is defined elsewhere (e.g., MainActivity or shared file)
// enum class GameState { PLAYING, GAME_OVER }

// Define a simple data class for tap events passed between threads
data class TapEvent(val x: Float, val y: Float)

// --- Data Classes ---
data class GameBottle(
    var id: Long = 0,
    var xPercent: Float = 0f, // Represents CENTER X percentage
    var yPx: Float = 0f,      // Position in pixels from top (CENTER Y)
    var color: Int = AndroidColor.TRANSPARENT, // Primarily for fallback drawing
    var speedFactorPxPerSec: Float = 0f,
    var active: Boolean = false, // Is this bottle currently in play?
    var widthPx: Float = 0f, // Width in pixels
    var heightPx: Float = 0f // Height in pixels
) {
    // Resets a pooled bottle to an active state
    fun reset(startXCenterPercent: Float, startYCenterPx: Float, startColorInt: Int, bottleW: Float, bottleH: Float, baseSpeedPx: Float) {
        id = System.nanoTime() // Unique ID for this instance
        xPercent = startXCenterPercent
        yPx = startYCenterPx
        color = startColorInt
        widthPx = bottleW
        heightPx = bottleH
        // Calculate speed based on base speed and a random factor
        speedFactorPxPerSec = (Random.nextFloat() * 0.5f + 0.8f) * baseSpeedPx
        active = true
    }

    // Updates the bottle's position and returns true if still active/on-screen
    fun update(deltaTimeSec: Float, viewHeight: Int): Boolean {
        if (!active) return true // Already inactive
        // Move down based on speed and time delta
        yPx += speedFactorPxPerSec * deltaTimeSec
        // Check if bottom edge is below the screen viewHeight
        if (yPx - heightPx / 2 > viewHeight) {
            active = false // Deactivate if missed
            return false // Return false indicating it went off-screen
        }
        return true // Still active and on-screen
    }

    // Calculates the drawing rectangle based on current center position and dimensions
    fun getRect(viewWidth: Int): RectF {
        val currentXPx = xPercent * viewWidth // Center X in pixels
        return RectF(
            currentXPx - widthPx / 2, // Left edge
            yPx - heightPx / 2,       // Top edge
            currentXPx + widthPx / 2, // Right edge
            yPx + heightPx / 2        // Bottom edge
        )
    }
}


data class BurstAnimation(
    var id: Long = 0,
    var xPx: Float = 0f, // Center X position of the burst
    var yPx: Float = 0f, // Center Y position of the burst
    var startTimeMs: Long = 0L, // When the animation started
    var active: Boolean = false // Is this animation currently playing?
) {
    val durationMs = 300L // How long the animation lasts in milliseconds

    // Resets a pooled animation object
    fun reset(px: Float, py: Float) {
        id = System.nanoTime(); xPx = px; yPx = py; startTimeMs = System.currentTimeMillis(); active = true
    }

    // Checks if the animation duration has passed
    fun hasFinished(currentTimeMs: Long): Boolean = currentTimeMs - startTimeMs > durationMs

    // Calculates the alpha (transparency) based on elapsed time for fade-out effect
    fun getCurrentAlpha(currentTimeMs: Long): Int {
        val elapsed = currentTimeMs - startTimeMs
        if (elapsed >= durationMs) return 0 // Fully faded out
        // Linear fade out
        return (255 * (1.0f - elapsed.toFloat() / durationMs)).toInt().coerceIn(0, 255)
    }
}

// --- GameSurfaceView Implementation ---
class GameSurfaceView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    // Coroutine scope dedicated to the game loop, using a SupervisorJob
    private val gameCoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var gameLoopJob: Job? = null // Holds the reference to the running game loop Job

    // State flows to communicate game state back to the Compose UI
    private val _scoreFlow = MutableStateFlow(0) // Private mutable state
    val scoreFlow: StateFlow<Int> = _scoreFlow // Public read-only state flow
    private val _gameStateFlow = MutableStateFlow(GameState.PLAYING)
    val gameStateFlow: StateFlow<GameState> = _gameStateFlow

    // Object management: Lists for active objects, Deques for pools
    private val activeBottles = mutableListOf<GameBottle>() // List of bottles currently falling
    private val bottlePool = ArrayDeque<GameBottle>() // Pool of inactive bottles for reuse
    private val activeBursts = mutableListOf<BurstAnimation>() // List of currently visible burst animations
    private val burstPool = ArrayDeque<BurstAnimation>() // Pool of inactive burst animations

    // View state variables
    @Volatile private var isSurfaceReady = false // Flag indicating if the drawing surface is valid
    private var viewWidth = 0
    private var viewHeight = 0
    private var lastSpawnTimeMs = 0L // Timestamp of the last bottle spawn

    // Game parameters
    private val targetFps = 60 // Target frames per second
    private val targetFrameTimeMillis = 1000L / targetFps // Target time per frame in ms
    private val baseSpeedDpPerSec = 100f // Base falling speed in Dp/sec before intensity scaling
    private var baseSpeedPxPerSec = 0f // Base speed converted to Px/sec
    private val bottleWidthDp = 40f // Desired bottle width in Dp
    private var bottleWidthPx = 0f
    private var bottleHeightPx = 0f // Calculated based on bitmap aspect ratio
    private var initialBottleOffsetYPx = 0f // Y-coordinate where bottles start (above screen)
    private var spawnDelayMillis = 1000L // Delay between bottle spawns
    @Volatile private var intensity = 1f // Current game intensity (0-10), controlled by Compose

    // Drawing tools
    private val bottlePaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }
    private val burstPaint = Paint().apply { isAntiAlias = true; isFilterBitmap = true }

    // Bitmap assets
    private var backgroundBitmap: Bitmap? = null
    private var bottleBitmap: Bitmap? = null
    private var bottleSrcRect: Rect? = null // Source Rect for drawing the entire bottle bitmap
    private var splashBitmap: Bitmap? = null
    private var splashSrcRect: Rect? = null // Source Rect for drawing the entire splash bitmap
    private var splashWidthPx = 0f
    private var splashHeightPx = 0f // Calculated based on splash bitmap aspect ratio

    // Callbacks for triggering sounds (provided by Compose UI)
    var playBurstSound: () -> Unit = {}
    var playMissSound: () -> Unit = {}

    // Thread-safe queue for receiving tap events from the UI thread
    private val tapEventQueue = ConcurrentLinkedQueue<TapEvent>()

    init {
        holder.addCallback(this) // Register for SurfaceHolder lifecycle events
        isFocusable = true // Ensure the view can receive focus and handle input
        Log.d("GameSurfaceView", "Init - View Initialized")
        loadBitmaps()
        calculateDimensions(resources.displayMetrics.density) // Calculate initial pixel sizes
    }

    // Load bitmap assets from drawable resources
    private fun loadBitmaps() {
        Log.d("GameSurfaceView", "Loading Bitmaps...")
        try {
            backgroundBitmap = BitmapFactory.decodeResource(resources, R.drawable.game_background)
            // Make sure 'bottle.png' and 'splash.png' (or your files) exist in res/drawable
            bottleBitmap = BitmapFactory.decodeResource(resources, R.drawable.bottle)
            splashBitmap = BitmapFactory.decodeResource(resources, R.drawable.splash)

            if (bottleBitmap != null) {
                bottleSrcRect = Rect(0, 0, bottleBitmap!!.width, bottleBitmap!!.height)
                Log.d("GameSurfaceView", "Bottle bitmap loaded successfully (${bottleBitmap!!.width}x${bottleBitmap!!.height})")
            } else { Log.e("GameSurfaceView", "Failed to load bottle bitmap!") }

            if (splashBitmap != null) {
                splashSrcRect = Rect(0, 0, splashBitmap!!.width, splashBitmap!!.height)
                Log.d("GameSurfaceView", "Splash bitmap loaded successfully (${splashBitmap!!.width}x${splashBitmap!!.height})")
            } else { Log.e("GameSurfaceView", "Failed to load splash bitmap!") }

            if (backgroundBitmap == null) { Log.e("GameSurfaceView", "Failed to load background bitmap!") }
            else { Log.d("GameSurfaceView", "Background bitmap loaded successfully") }

        } catch (e: Exception) { Log.e("GameSurfaceView", "Error loading bitmaps", e) }
    }

    // Calculate pixel sizes and speeds based on device density and asset ratios
    private fun calculateDimensions(density: Float) {
        baseSpeedPxPerSec = baseSpeedDpPerSec * density
        bottleWidthPx = bottleWidthDp * density

        // Calculate bottle height from bitmap aspect ratio (if available)
        if (bottleBitmap != null && bottleBitmap!!.width > 0) {
            val bottleAspectRatio = bottleBitmap!!.height.toFloat() / bottleBitmap!!.width
            bottleHeightPx = bottleWidthPx * bottleAspectRatio
        } else {
            Log.w("GameSurfaceView", "Bottle bitmap not loaded, using fallback height")
            bottleHeightPx = (bottleWidthDp * 2) * density // Arbitrary fallback if bitmap fails
        }

        // Calculate splash size based on splash bitmap ratio, scaled relative to bottle width
        if (splashBitmap != null && splashBitmap!!.width > 0) {
            splashWidthPx = bottleWidthPx * 1.5f // Splash slightly larger than bottle
            val splashAspectRatio = splashBitmap!!.height.toFloat() / splashBitmap!!.width
            splashHeightPx = splashWidthPx * splashAspectRatio
        } else {
            Log.w("GameSurfaceView", "Splash bitmap not loaded, using fallback size")
            splashWidthPx = bottleWidthPx * 1.5f; splashHeightPx = bottleHeightPx * 1.5f // Fallback
        }

        // Start bottle center just off the top edge
        initialBottleOffsetYPx = -bottleHeightPx / 2
        Log.i("GameSurfaceView", "Calculated Dims: Bottle=(${bottleWidthPx.roundToInt()}x${bottleHeightPx.roundToInt()}px), Splash=(${splashWidthPx.roundToInt()}x${splashHeightPx.roundToInt()}px), BaseSpeed=${baseSpeedPxPerSec.roundToInt()}px/s")
    }


    // --- Game Loop Management ---
    private fun startGameLoop() {
        // Prevent starting multiple loops or starting when not ready/game over
        if (gameLoopJob?.isActive == true) {
            Log.w("GameSurfaceView", "Start loop requested but already running.")
            return
        }
        if (!isSurfaceReady) {
            Log.w("GameSurfaceView", "Start loop requested but surface not ready.")
            return
        }
        if (gameStateFlow.value == GameState.GAME_OVER) {
            Log.w("GameSurfaceView", "Start loop requested but game is already over.")
            return
        }

        gameLoopJob = gameCoroutineScope.launch {
            Log.i("GameSurfaceView", "Starting game loop...")
            var lastFrameTimeNanos = System.nanoTime()

            while (isActive && gameStateFlow.value == GameState.PLAYING) {
                // Safety check: Exit loop if surface becomes unavailable mid-frame
                if (!isSurfaceReady) {
                    Log.w("GameSurfaceView", "Surface became unavailable during loop.")
                    break
                }

                val currentTimeNanos = System.nanoTime()
                // Ensure delta is non-negative (can happen with clock adjustments)
                val deltaNanos = (currentTimeNanos - lastFrameTimeNanos).coerceAtLeast(0)
                lastFrameTimeNanos = currentTimeNanos

                // Convert delta to seconds, clamp to prevent instability from huge frame drops
                val deltaSec = (deltaNanos / 1_000_000_000.0f).coerceIn(0.001f, 0.05f)

                // --- Perform game updates ---
                updateGameState(deltaSec)

                // Check if game ended during the update phase
                if (gameStateFlow.value != GameState.PLAYING) break

                // --- Perform drawing ---
                drawGame(holder)

                // --- Frame timing delay ---
                val cycleTimeMillis = deltaNanos / 1_000_000
                val sleepTimeMillis = targetFrameTimeMillis - cycleTimeMillis
                if (sleepTimeMillis > 0) {
                    delay(sleepTimeMillis) // Wait if we finished early
                } else {
                    yield() // Give other coroutines a chance if we're running behind
                }
            }
            Log.i("GameSurfaceView", "Game loop exited naturally. Final state: ${gameStateFlow.value}")
        }
        // Log if the loop crashes for unexpected reasons
        gameLoopJob?.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) {
                Log.e("GameSurfaceView", "Game loop CRASHED!", cause)
                _gameStateFlow.value = GameState.GAME_OVER // Force game over state on crash
            } else {
                Log.i("GameSurfaceView", "Game loop job completed or cancelled.")
            }
        }
    }

    // Requests cancellation of the game loop coroutine
    private fun stopGameLoop() {
        if (gameLoopJob == null) return
        Log.i("GameSurfaceView", "Requesting game loop stop...")
        gameLoopJob?.cancel() // Non-blocking cancellation request
        gameLoopJob = null
    }

    // --- Core Game State Update Logic ---
    private fun updateGameState(deltaTimeSec: Float) {
        // Avoid updates if view dimensions are not yet valid
        if (viewHeight <= 0 || viewWidth <= 0) return

        // Process taps received from the UI thread
        processTapQueue()

        // Update active bottles (movement and miss check)
        val bottleIterator = activeBottles.listIterator() // Use listIterator for safe removal
        var bottleMissedThisFrame = false
        while (bottleIterator.hasNext()) {
            val bottle = bottleIterator.next()
            if (!bottle.update(deltaTimeSec, viewHeight)) { // update returns false if bottle went off screen
                bottleIterator.remove() // Remove from active list
                releaseBottle(bottle)   // Add back to pool for reuse
                bottleMissedThisFrame = true // Flag that a miss occurred
            }
        }

        // Update active burst animations (check if finished)
        val burstIterator = activeBursts.listIterator()
        val currentTimeMs = System.currentTimeMillis() // Get current time once for all checks
        while (burstIterator.hasNext()) {
            val burst = burstIterator.next()
            if (burst.hasFinished(currentTimeMs)) {
                burstIterator.remove()
                releaseBurst(burst) // Return to pool
            }
        }

        // If a bottle was missed, trigger game over
        if (bottleMissedThisFrame) {
            if (_gameStateFlow.value != GameState.GAME_OVER) { // Only trigger once
                Log.i("GameSurfaceView", "Game Over triggered by missed bottle.")
                _gameStateFlow.value = GameState.GAME_OVER
                playMissSound() // Play miss sound via callback
                stopGameLoop() // Stop the loop immediately
            }
            return // Don't proceed to spawning if game is over
        }

        // Spawn new bottles periodically based on current delay
        spawnDelayMillis = max(150L, (1000 - intensity * 80).toLong()) // Faster spawns at higher intensity
        if (currentTimeMs - lastSpawnTimeMs > spawnDelayMillis) {
            spawnBottle()
            lastSpawnTimeMs = currentTimeMs // Update last spawn time
        }
    }

    // --- Processes Tap Events Queued by onTouchEvent ---
    private fun processTapQueue() {
        while(true) { // Process all events in the queue for this frame
            val tap = tapEventQueue.poll() ?: break // Get next tap or exit if queue empty
            Log.d("GameSurfaceViewTap", "Processing queued tap at (${tap.x}, ${tap.y})")
            var bottleTapped = false
            // Check against active bottles, iterating backwards for safe removal
            for (i in activeBottles.indices.reversed()) {
                if (i >= activeBottles.size) continue // Needed after potential removal

                val bottle = activeBottles[i]
                val bottleRect = bottle.getRect(viewWidth) // Get the bottle's bounding box

                // Define the tap target area (slightly larger than the visual bottle)
                val expandedTapRect = RectF(bottleRect)
                val horizontalPadding = bottle.widthPx * 0.2f // Add 20% horizontal padding
                val verticalPadding = bottle.heightPx * 0.2f // Add 20% vertical padding
                expandedTapRect.inset(-horizontalPadding, -verticalPadding)

                Log.d("GameSurfaceViewTap", " Checking bottle $i (Y:${bottle.yPx.toInt()}) rect: [${bottleRect.left.toInt()}-${bottleRect.right.toInt()}, ${bottleRect.top.toInt()}-${bottleRect.bottom.toInt()}], Tap area: [${expandedTapRect.left.toInt()}-${expandedTapRect.right.toInt()}, ${expandedTapRect.top.toInt()}-${expandedTapRect.bottom.toInt()}]")

                // Check if the tap coordinates are within the expanded tap area
                if (expandedTapRect.contains(tap.x, tap.y)) {
                    Log.i("GameSurfaceViewTap", ">>> HIT bottle $i")
                    triggerBurstAnimation(bottleRect.centerX(), bottleRect.centerY()) // Create burst at bottle center
                    activeBottles.removeAt(i) // Remove the tapped bottle from active list
                    releaseBottle(bottle) // Return it to the pool
                    _scoreFlow.value += 1 // Increase score
                    playBurstSound() // Play sound effect
                    bottleTapped = true
                    break // Stop checking other bottles for this tap
                }
            }
            if (!bottleTapped) { Log.d("GameSurfaceViewTap", "--- Tap missed all bottles") }
        }
    }

    // --- Creates and manages a burst animation effect ---
    private fun triggerBurstAnimation(xPx: Float, yPx: Float) {
        val burst = obtainBurst() // Reuse or create a new burst object
        burst.reset(xPx, yPx) // Set its position and start animation timer
        activeBursts.add(burst) // Add to list to be drawn
    }

    // --- Creates and positions a new bottle ---
    private fun spawnBottle() {
        // Do not spawn if view dimensions aren't set
        if (viewWidth <= 0) {
            Log.w("GameSurfaceViewSpawn", "Attempted to spawn bottle before viewWidth known.")
            return
        }

        val bottle = obtainBottle() // Get a bottle from the pool or create a new one

        // Calculate valid spawn range horizontally (centering)
        val halfWidthPercent = (bottleWidthPx / 2f) / viewWidth
        val minSpawnCenterPercent = halfWidthPercent
        val maxSpawnCenterPercent = 1.0f - halfWidthPercent

        // Random horizontal position, clamped within valid range
        val spawnCenterPercent = if (maxSpawnCenterPercent >= minSpawnCenterPercent)
            Random.nextFloat().coerceIn(minSpawnCenterPercent, maxSpawnCenterPercent)
        else 0.5f // Default to screen center if view is too narrow

        // Calculate current speed based on intensity (adjust scaling factor as needed)
        val currentIntensitySpeed = baseSpeedPxPerSec * (1f + (intensity / 10f) * 2f) // Example scaling

        // Initialize the bottle's state
        bottle.reset(spawnCenterPercent, initialBottleOffsetYPx, 0, bottleWidthPx, bottleHeightPx, currentIntensitySpeed)

        activeBottles.add(bottle) // Add the newly configured bottle to the active list
        // Log.d("GameSurfaceViewSpawn", "Spawned bottle ${bottle.id} at xPercent=${"%.2f".format(spawnCenterPercent)}")
    }

    // --- Handles drawing the entire game state to the Canvas ---
    private fun drawGame(holder: SurfaceHolder) {
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas() // Acquire the canvas for drawing
            if (canvas == null) {
                Log.v("GameSurfaceViewDraw", "lockCanvas returned null, skipping draw.")
                return // Skip frame if canvas unavailable
            }

            // 1. Draw Background
            drawBackgroundCropped(canvas)

            // 2. Draw Bottles
            val currentBottleBitmap = bottleBitmap
            val srcBottleRect = bottleSrcRect
            if (currentBottleBitmap != null && srcBottleRect != null) {
                // Use bitmap if loaded
                for (bottle in activeBottles) {
                    val destRect = bottle.getRect(viewWidth) // Get where to draw this bottle
                    canvas.drawBitmap(currentBottleBitmap, srcBottleRect, destRect, bottlePaint)
                }
            } else {
                // Fallback: Draw simple colored rectangles if bitmap failed
                bottlePaint.style = Paint.Style.FILL
                bottlePaint.color = AndroidColor.MAGENTA // Use a distinct color for debugging
                for (bottle in activeBottles) {
                    canvas.drawRect(bottle.getRect(viewWidth), bottlePaint)
                }
                Log.w("GameSurfaceViewDraw", "Drawing fallback bottle shapes, bitmap not loaded.")
            }


            // 3. Draw Burst Animations
            val currentSplashBitmap = splashBitmap
            val srcSplashRect = splashSrcRect
            if (currentSplashBitmap != null && srcSplashRect != null) {
                val currentTimeMs = System.currentTimeMillis()
                for (burst in activeBursts) {
                    val alpha = burst.getCurrentAlpha(currentTimeMs) // Calculate fade effect
                    if (alpha > 0) {
                        burstPaint.alpha = alpha // Apply transparency
                        // Center the splash bitmap at the burst location using calculated splash size
                        val burstDestRect = RectF(burst.xPx - splashWidthPx / 2, burst.yPx - splashHeightPx / 2, burst.xPx + splashWidthPx / 2, burst.yPx + splashHeightPx / 2)
                        canvas.drawBitmap(currentSplashBitmap, srcSplashRect, burstDestRect, burstPaint)
                    }
                }
            } else {
                // Could add a fallback burst drawing (e.g., expanding circle) if needed
                // Log.w("GameSurfaceViewDraw", "Splash bitmap not loaded, bursts not drawn.")
            }

        } catch (e: Exception) {
            // Catch exceptions during drawing phase
            Log.e("GameSurfaceViewDraw", "Exception during drawing cycle", e)
        } finally {
            // Critical: ALWAYS unlock the canvas in a finally block
            if (canvas != null) {
                try {
                    holder.unlockCanvasAndPost(canvas)
                } catch (e: Exception) {
                    // This can happen if the surface is destroyed concurrently
                    isSurfaceReady = false // Mark surface as invalid
                    stopGameLoop() // Stop the loop if we can't draw anymore
                    Log.e("GameSurfaceViewDraw", "Exception unlocking canvas. Surface might be destroyed.", e)
                }
            }
        }
    }

    // Helper method to draw the background bitmap, cropped to fit the view's aspect ratio
    private fun drawBackgroundCropped(canvas: Canvas) {
        val bg = backgroundBitmap ?: run {
            // Draw solid color if no bitmap
            canvas.drawColor(AndroidColor.DKGRAY)
            return
        }
        val bmpW = bg.width
        val bmpH = bg.height
        val cvW = canvas.width
        val cvH = canvas.height

        val dst = Rect(0, 0, cvW, cvH) // Draw destination is always the full canvas

        // Calculate source rect to crop center and maintain aspect ratio
        val bmpRatio = bmpW.toFloat() / bmpH
        val cvRatio = cvW.toFloat() / cvH
        val src = Rect()
        if (bmpRatio > cvRatio) { // Bitmap is wider than canvas aspect ratio, crop width
            val newW = (bmpH * cvRatio).toInt() // Calculate new width based on canvas height
            val xOff = (bmpW - newW) / 2 // Center horizontally
            src.set(xOff, 0, xOff + newW, bmpH)
        } else { // Bitmap is taller than canvas aspect ratio, crop height
            val newH = (bmpW / cvRatio).toInt() // Calculate new height based on canvas width
            val yOff = (bmpH - newH) / 2 // Center vertically
            src.set(0, yOff, bmpW, yOff + newH)
        }
        // Draw the calculated source portion of the bitmap onto the destination rect (whole canvas)
        canvas.drawBitmap(bg, src, dst, null) // null paint uses default bitmap drawing
    }

    // --- Object Pooling Methods using ArrayDeque ---
    private fun obtainBottle(): GameBottle = bottlePool.removeFirstOrNull() ?: GameBottle()
    private fun releaseBottle(bottle: GameBottle) { bottle.active = false; bottlePool.addLast(bottle) }
    private fun obtainBurst(): BurstAnimation = burstPool.removeFirstOrNull() ?: BurstAnimation()
    private fun releaseBurst(burst: BurstAnimation) { burst.active = false; burstPool.addLast(burst) }

    // --- Public Functions Accessible from Compose/Activity ---
    // Method to handle touch input events received from the Android View system
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return super.onTouchEvent(event) // Ignore null events

        // Process the initial touch down (ACTION_DOWN)
        if (event.action == MotionEvent.ACTION_DOWN) {
            // Only process taps if the game is currently playing
            if (gameStateFlow.value == GameState.PLAYING) {
                handleTap(event.x, event.y) // Pass tap coordinates to handler logic
                return true // Indicate that we've handled this touch event
            }
        }
        // For other event actions (MOVE, UP, CANCEL), let the default system handler manage them
        return super.onTouchEvent(event)
    }

    // Queues a tap event for processing by the game loop thread
    fun handleTap(touchXPx: Float, touchYPx: Float) {
        // Log that the tap was received by this specific method
        Log.d("GameSurfaceView", "handleTap received raw coords: ($touchXPx, $touchYPx)")
        if (gameStateFlow.value == GameState.PLAYING) {
            // Add the event to the thread-safe queue
            tapEventQueue.offer(TapEvent(touchXPx, touchYPx))
        } else {
            Log.d("GameSurfaceView", "Tap ignored because game state is not PLAYING")
        }
    }

    // Called by Compose to update the game's intensity setting
    fun updateIntensity(newIntensity: Float) {
        this.intensity = newIntensity.coerceIn(0f, 10f) // Clamp value
        // Log.d("GameSurfaceView", "Intensity updated to: ${this.intensity}")
    }

    // Called by Compose to reset the game to its initial state
    fun resetGame() {
        Log.i("GameSurfaceView", "Resetting game state...")
        gameCoroutineScope.launch { // Ensure state changes happen within the game's scope
            stopGameLoop() // Stop the current loop first

            _scoreFlow.value = 0
            intensity = 1f // Reset intensity slider effect

            // Clear active objects and return them to pools
            activeBottles.forEach { releaseBottle(it) }
            activeBottles.clear()
            activeBursts.forEach { releaseBurst(it) }
            activeBursts.clear()
            tapEventQueue.clear() // Clear any pending taps from the previous session

            _gameStateFlow.value = GameState.PLAYING // Set state back to playing
            lastSpawnTimeMs = System.currentTimeMillis() // Reset spawn timer

            // Attempt to restart the loop if the drawing surface is still available
            if (isSurfaceReady) {
                Log.i("GameSurfaceView", "Surface ready, restarting game loop after reset.")
                startGameLoop()
            } else {
                Log.w("GameSurfaceView", "Reset game completed, but surface not ready. Loop will start on surfaceCreated.")
            }
        }
    }

    // Called by Compose's DisposableEffect to clean up resources
    fun cleanup() {
        Log.i("GameSurfaceView", "Cleanup requested. Stopping loop and cancelling scope.")
        stopGameLoop() // Ensure the loop is stopped
        gameCoroutineScope.cancel("GameSurfaceView cleanup called") // Cancel all coroutines in the scope
    }


    // --- SurfaceHolder Callback Methods ---
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.i("GameSurfaceView", "Surface created.")
        isSurfaceReady = true // Mark surface as ready for drawing
        // If the game was already in a PLAYING state (e.g., after rotation or pause), start the loop
        if (_gameStateFlow.value == GameState.PLAYING) {
            startGameLoop()
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.i("GameSurfaceView", "Surface changed: Width=$width, Height=$height")
        // Store new dimensions and recalculate pixel-dependent values
        viewWidth = width
        viewHeight = height
        calculateDimensions(resources.displayMetrics.density)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.i("GameSurfaceView", "Surface destroyed.")
        isSurfaceReady = false // Mark surface as unavailable
        stopGameLoop() // Critically important to stop the loop trying to draw
    }
}
