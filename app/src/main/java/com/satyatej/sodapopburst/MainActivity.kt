package com.satyatej.sodapopburst

import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.satyatej.sodapopburst.ui.theme.SodaPopBurstTheme
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.roundToInt

// Define GameState here or move to a separate shared file
enum class GameState { PLAYING, GAME_OVER }

class MainActivity : ComponentActivity() {

    private var soundPool: SoundPool? = null
    private var burstSoundId: Int = 0
    private var missSoundId: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupSoundPool()
        setContent {
            SodaPopBurstTheme {
                // --- Sound Pool Management ---
                DisposableEffect(Unit) {
                    onDispose {
                        Log.d("MainActivity", "onDispose - Releasing SoundPool")
                        releaseSoundPool()
                    }
                }

                // --- Main Game Screen ---
                GameScreenWithSurfaceView(
                    playBurstSound = { playSound(burstSoundId) },
                    playMissSound = { playSound(missSoundId) }
                )
            }
        }
    }

    private fun setupSoundPool() {
        soundPool = SoundPool.Builder()
            .setMaxStreams(5)
            .build()
        try {
            burstSoundId = soundPool!!.load(this, R.raw.burst_sound, 1)
            missSoundId = soundPool!!.load(this, R.raw.miss_sound, 1)
            Log.d("MainActivity", "Sounds loaded: Burst=$burstSoundId, Miss=$missSoundId")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to load sounds", e)
            burstSoundId = 0
            missSoundId = 0
        }
    }

    private fun playSound(soundId: Int) {
        if (soundId != 0) {
            soundPool?.play(soundId, 0.8f, 0.8f, 1, 0, 1f) // Slightly lower volume
        } else {
            Log.w("MainActivity", "Attempted to play sound with ID 0")
        }
    }

    private fun releaseSoundPool() {
        soundPool?.release()
        soundPool = null
        Log.d("MainActivity", "SoundPool released")
    }

    override fun onDestroy() {
        super.onDestroy()
        // Release handled by DisposableEffect
        Log.d("MainActivity", "onDestroy")
    }
}

@Composable
fun GameScreenWithSurfaceView(
    playBurstSound: () -> Unit,
    playMissSound: () -> Unit
) {
    // Hold a reference to the GameSurfaceView instance using remember
    val gameSurfaceView = remember { mutableStateOf<GameSurfaceView?>(null) }

    // --- State Collection ---
    val score by gameSurfaceView.value?.scoreFlow?.collectAsState() ?: remember { mutableStateOf(0) }
    val gameState by gameSurfaceView.value?.gameStateFlow?.collectAsState() ?: remember { mutableStateOf(GameState.PLAYING) } // Default to PLAYING initially

    // Local UI state for the intensity slider
    var intensitySliderValue by remember { mutableStateOf(1f) }

    // --- Lifecycle Management for GameSurfaceView's CoroutineScope ---
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, gameSurfaceView.value) {
        val view = gameSurfaceView.value
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                Log.d("MainActivityComposable", "ON_DESTROY detected, cleaning up view")
                view?.cleanup() // Call cleanup when the Activity is destroyed
            }
            // Add ON_PAUSE / ON_RESUME handling if needed by calling corresponding methods on view
            // if (event == Lifecycle.Event.ON_PAUSE) view?.pauseGameLoop()
            // if (event == Lifecycle.Event.ON_RESUME) view?.resumeGameLoop()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            Log.d("MainActivityComposable", "onDispose - Removing observer")
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Cleanup might be called here too if composable disposes before Activity
            // view?.cleanup() // Optional: depends on desired cleanup timing
        }
    }


    // --- Effect to update GameSurfaceView's intensity when slider changes ---
    LaunchedEffect(intensitySliderValue, gameSurfaceView.value) {
        gameSurfaceView.value?.updateIntensity(intensitySliderValue)
    }

    // --- UI Layout ---
    Box(modifier = Modifier.fillMaxSize()) { // Layer background and game elements

        // 1. Background Image
        Image(
            painter = painterResource(id = R.drawable.game_background),
            contentDescription = "Game Background",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // 2. Game Area and UI Overlay Column
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Score Display
            Text(
                text = "Score: $score",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // Game SurfaceView Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f) // Takes up available space
                    .pointerInput(Unit) { // Handle taps within this Box
                        detectTapGestures {
                            gameSurfaceView.value?.handleTap(it.x, it.y) // Pass offset x, y
                        }
                    }
            ) {
                AndroidView(
                    factory = { context ->
                        // Create and store the reference
                        GameSurfaceView(context).apply {
                            // Pass sound lambdas during creation
                            this.playBurstSound = playBurstSound
                            this.playMissSound = playMissSound
                            gameSurfaceView.value = this // Update the state holder
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // Intensity Slider (only visible when playing)
            if (gameState == GameState.PLAYING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.medium)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Text("Speed:", color = Color.White, modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = intensitySliderValue,
                        onValueChange = { intensitySliderValue = it }, // Update local UI state
                        valueRange = 0f..10f,
                        steps = 9,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = intensitySliderValue.roundToInt().toString(),
                        color = Color.White,
                        modifier = Modifier.padding(start = 8.dp).width(30.dp) // Wider text area
                    )
                }
            } else {
                // Reserve space for the slider when game is over to prevent layout jumps
                Spacer(modifier = Modifier.height(56.dp).fillMaxWidth()) // Adjust height as needed
            }
        } // End Column

        // --- Game Over Overlay --- (Drawn on top)
        if (gameState == GameState.GAME_OVER) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.75f)), // Darker overlay
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .padding(32.dp)
                ) {
                    Text("Game Over!", fontSize = 36.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Final Score: $score", fontSize = 28.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            gameSurfaceView.value?.resetGame() // Call reset on the view instance
                            intensitySliderValue = 1f // Reset slider UI state
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text("Play Again", fontSize = 18.sp)
                    }
                }
            }
        }
    } // End Root Box
}
