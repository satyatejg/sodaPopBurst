package com.satyatej.sodapopburst // <-- *** REPLACE with YOUR actual package name ***

import android.content.Context
import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas // Import Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable // Good practice for score if activity restarts
import androidx.compose.runtime.snapshots.SnapshotStateList // Needed for mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.satyatej.sodapopburst.R // <-- *** Ensure this imports YOUR project's generated R class ***
import kotlinx.coroutines.delay // Still potentially needed for non-frame logic? Unlikely here.
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random

// Data class for bottles (simple version)
data class Bottle(
    val id: Long = System.nanoTime(),
    var x: Float, // Horizontal position (percentage of screen width)
    var y: Float, // Vertical position (pixels from top)
    val color: Color,
    val speedFactor: Float = Random.nextFloat() * 0.5f + 0.8f
)

// Predefined soda colors
val sodaColors = listOf(
    Color(0xFFE53935), // Red
    Color(0xFF43A047), // Green
    Color(0xFF1E88E5), // Blue
    Color(0xFFFDD835), // Yellow
    Color(0xFF8E24AA), // Purple
    Color(0xFFFF7043)  // Orange
)

// --- Main Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SodaPopBurstTheme {
                GameScreen()
            }
        }
    }
}

// --- Game Screen Composable ---
@Composable
fun GameScreen() {
    val context = LocalContext.current
    val density = LocalDensity.current

    // --- Calculated Pixel Values (Using slower speed) ---
    val baseSpeedDp = 60.dp
    val baseSpeedPxPerSec = remember(density) { with(density) { baseSpeedDp.toPx() } }
    val bottleWidthDp = 40.dp
    val bottleHeightDp = 80.dp
    val initialBottleOffsetYDp = -80.dp
    val bottleWidthPx = remember(density) { with(density) { bottleWidthDp.toPx() } }
    val bottleHeightPx = remember(density) { with(density) { bottleHeightDp.toPx() } }
    val initialBottleOffsetYPx = remember(density) { with(density) { initialBottleOffsetYDp.toPx() } }
    val bottleDrawSize = remember(bottleWidthPx, bottleHeightPx) { Size(bottleWidthPx, bottleHeightPx) }

    // --- Game State (Using SnapshotStateList for bottles) ---
    var score by rememberSaveable { mutableStateOf(0) } // Use rememberSaveable for score persistence
    var intensity by remember { mutableStateOf(1f) }
    val bottles = remember { mutableStateListOf<Bottle>() }
    var screenSize by remember { mutableStateOf(IntSize.Zero) }
    var gameState by remember { mutableStateOf(GameState.PLAYING) }
    var lastSpawnTime by remember { mutableStateOf(0L) }

    // --- Sound Effects Setup ---
    val soundPool = remember {
        SoundPool.Builder().setMaxStreams(5).build()
    }
    val burstSoundId = remember(context) {
        try { soundPool.load(context, R.raw.burst_sound, 1) }
        catch (e: Exception) { Log.e("SoundLoad", "Failed to load burst_sound", e); 0 }
    }
    val missSoundId = remember(context) {
        try { soundPool.load(context, R.raw.miss_sound, 1) }
        catch (e: Exception) { Log.e("SoundLoad", "Failed to load miss_sound", e); 0 }
    }
    DisposableEffect(Unit) {
        onDispose { Log.d("SoundPool", "Releasing SoundPool"); soundPool.release() }
    }
    fun playSound(soundId: Int) {
        if (soundId != 0) { soundPool.play(soundId, 1f, 1f, 1, 0, 1f) }
        else { Log.w("SoundPlay", "Attempted to play sound with ID 0") }
    }

    // --- Game Loop using withFrameNanos ---
    LaunchedEffect(gameState, intensity, baseSpeedPxPerSec, bottleWidthPx, initialBottleOffsetYPx) {
        if (gameState == GameState.PLAYING) {
            Log.i("GameLoop", "LaunchedEffect Started (withFrameNanos) for PLAYING state.")
            var lastFrameTimeNanos = -1L // Marker for first frame

            while (isActive && gameState == GameState.PLAYING) {
                // Wait for next frame draw signal & get precise timestamp
                val frameTimeNanos = withFrameNanos { it }

                // Only run logic after the first frame has provided a timestamp
                if (lastFrameTimeNanos != -1L) {
                    val deltaTime = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000_000.0f
                    val clampedDeltaTime = deltaTime.coerceAtMost(0.05f) // Keep safety clamp

                    // Check screen size before proceeding
                    if (screenSize.height <= 0) {
                        // Log.v("GameLoop", "Screen size invalid, skipping update.")
                        // No delay needed, withFrameNanos waits for next frame anyway
                    } else {
                        // --- Update Game State ---
                        val speedMultiplier = 1f + (intensity / 10f) * 4f
                        val fallSpeedPx = baseSpeedPxPerSec * speedMultiplier * clampedDeltaTime

                        // Update Bottle Positions
                        val iterator = bottles.iterator()
                        while (iterator.hasNext()) {
                            val bottle = iterator.next()
                            bottle.y += fallSpeedPx * bottle.speedFactor

                            // Check miss condition
                            if (bottle.y > screenSize.height) {
                                Log.d("GameLoop", "Bottle ${bottle.id} missed: Y=${String.format("%.2f", bottle.y)} > ScreenH=${screenSize.height}")
                                iterator.remove()
                                playSound(missSoundId)
                                gameState = GameState.GAME_OVER
                                break // Exit inner loop
                            }
                        }

                        if (gameState == GameState.GAME_OVER) break // Exit outer loop

                        // Spawn New Bottles
                        val spawnDelayMillis = max(200L, (1500 - intensity * 120).toLong())
                        if (System.currentTimeMillis() - lastSpawnTime > spawnDelayMillis && screenSize.width > 0) {
                            val randomXPercent = Random.nextFloat()
                            val maxXPx = screenSize.width - bottleWidthPx
                            val spawnXPx = (randomXPercent * maxXPx).coerceAtLeast(0f)
                            Log.d("GameSpawn", "Spawning new bottle at Y=$initialBottleOffsetYPx")
                            bottles.add(
                                Bottle(
                                    x = if (screenSize.width > 0) spawnXPx / screenSize.width else 0f,
                                    y = initialBottleOffsetYPx,
                                    color = sodaColors.random()
                                )
                            )
                            lastSpawnTime = System.currentTimeMillis()
                        }
                        // --- End Update Game State ---
                    }
                }
                // Update last frame time for next delta calculation
                lastFrameTimeNanos = frameTimeNanos
            } // End while loop
        } // End if PLAYING
        Log.i("GameLoop", "LaunchedEffect (withFrameNanos) finishing. GameState: $gameState")
    } // End LaunchedEffect


    // --- UI Layout ---
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { layoutCoordinates ->
                // Update screen size safely
                if (screenSize != layoutCoordinates.size && layoutCoordinates.size.height > 0) {
                    screenSize = layoutCoordinates.size
                    Log.i("Layout", "Screen size initialised: $screenSize")
                }
            }
    ) {
        // 1. Background Image
        Image(
            painter = painterResource(id = R.drawable.game_background),
            contentDescription = "Game Background",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 2. Tap Detection Layer
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(bottleWidthPx, bottleHeightPx, bottles.size) {
                    detectTapGestures { touchOffset ->
                        if (gameState == GameState.PLAYING && screenSize.width > 0) {
                            var bottleTapped = false
                            val iterator = bottles.listIterator(bottles.size)
                            while(iterator.hasPrevious() && !bottleTapped) {
                                val bottle = iterator.previous()
                                val bottleIndex = iterator.nextIndex()
                                val bottleXPx = bottle.x * screenSize.width
                                val bottleYPx = bottle.y
                                val tapRect = Rect(
                                    left = bottleXPx - (bottleWidthPx * 0.3f),
                                    top = bottleYPx - (bottleHeightPx * 0.5f),
                                    right = bottleXPx + bottleWidthPx + (bottleWidthPx * 0.3f),
                                    bottom = bottleYPx + (bottleHeightPx * 1.0f)
                                )
                                Log.d("TapDebug", "Tap at: ${touchOffset.x.toInt()}, ${touchOffset.y.toInt()}. Checking Bottle $bottleIndex (X:${bottleXPx.toInt()}, Y:${bottle.y.toInt()}) -> Rect: [L=${tapRect.left.toInt()}, T=${tapRect.top.toInt()}, R=${tapRect.right.toInt()}, B=${tapRect.bottom.toInt()}]")
                                if (tapRect.contains(touchOffset)) {
                                    Log.i("TapDebug", ">>> SUCCESSFUL TAP on Bottle Index $bottleIndex (Rect: ${tapRect.toString().replace(".0","")})")
                                    bottles.removeAt(bottleIndex)
                                    score++
                                    playSound(burstSoundId)
                                    bottleTapped = true
                                    break
                                }
                            }
                            if (!bottleTapped) { Log.d("TapDebug", "--- Tap at ${touchOffset.x.toInt()}, ${touchOffset.y.toInt()} missed all bottles.") }
                        }
                    }
                }
        )

        // 3. Canvas Layer for Drawing Bottles
        Canvas(modifier = Modifier.fillMaxSize()) {
            bottles.forEach { bottle ->
                val drawX = bottle.x * size.width
                val drawY = bottle.y
                if (drawY < size.height + bottleHeightPx && drawY > -bottleHeightPx * 2) {
                    drawRect(
                        color = bottle.color,
                        topLeft = Offset(drawX, drawY),
                        size = bottleDrawSize
                    )
                }
            }
        }

        // 4. UI Overlay (Score, Slider, Game Over)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Score Text
            Text(
                text = "Score: $score",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Intensity Slider
            if (gameState == GameState.PLAYING) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Speed:", color = Color.White, modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = intensity,
                        onValueChange = { intensity = it },
                        valueRange = 0f..10f,
                        steps = 9,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = intensity.roundToInt().toString(),
                        color = Color.White,
                        modifier = Modifier.padding(start = 8.dp).width(20.dp)
                    )
                }
            }

            // Game Over Screen
            if (gameState == GameState.GAME_OVER) {
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .background(Color.Black.copy(alpha = 0.7f), shape = MaterialTheme.shapes.large)
                        .padding(32.dp)
                ) {
                    Text("Game Over!", fontSize = 32.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Final Score: $score", fontSize = 24.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        // Reset Game State
                        score = 0
                        bottles.clear()
                        intensity = 1f
                        gameState = GameState.PLAYING
                        lastSpawnTime = System.currentTimeMillis() // Reset spawn timer correctly
                    }) {
                        Text("Play Again")
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
            }
        } // End UI Column

    } // End Root Box
} // End GameScreen


// --- Simple Theme ---
@Composable
fun SodaPopBurstTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6200EE),
            secondary = Color(0xFF03DAC6),
            tertiary = Color(0xFF3700B3)
        ),
        typography = Typography(),
        shapes = Shapes(),
        content = content
    )
}

// --- Game State Enum ---
enum class GameState {
    PLAYING,
    GAME_OVER
}
