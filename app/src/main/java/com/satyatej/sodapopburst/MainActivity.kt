package com.satyatej.sodapopburst

import android.media.SoundPool
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
// import androidx.compose.foundation.gestures.detectTapGestures // Not needed here
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState // <<<--- IMPORT ADDED ---<<<
// import androidx.compose.runtime.snapshots.SnapshotStateList // Not directly used here
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
// import androidx.compose.ui.input.pointer.pointerInput // Not needed here
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.satyatej.sodapopburst.R // Make sure this points to your R class
import com.satyatej.sodapopburst.ui.theme.SodaPopBurstTheme // Make sure this points to your Theme
// import kotlinx.coroutines.flow.collectLatest // Not used directly here
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
                    playBurstSound = { playSound(burstSoundId, "Burst") },
                    playMissSound = { playSound(missSoundId, "Miss") }
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

    private fun playSound(soundId: Int, soundName: String) {
        if (soundPool == null) {
            Log.e("MainActivity", "SoundPool not initialized when trying to play $soundName ($soundId)")
            return
        }
        if (soundId != 0) {
            val streamId = soundPool?.play(soundId, 0.8f, 0.8f, 1, 0, 1f)
            Log.d("MainActivity", "Playing sound '$soundName' (ID: $soundId, Stream: $streamId)")
            if (streamId == 0) {
                Log.e("MainActivity", "Failed to play sound '$soundName' (ID: $soundId) - play() returned 0")
            }
        } else {
            Log.w("MainActivity", "Attempted to play sound '$soundName' but ID is 0")
        }
    }


    private fun releaseSoundPool() {
        soundPool?.release()
        soundPool = null
        Log.d("MainActivity", "SoundPool released")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("MainActivity", "onDestroy")
    }
}

@Composable
fun GameScreenWithSurfaceView(
    playBurstSound: () -> Unit,
    playMissSound: () -> Unit
) {
    val gameSurfaceView = remember { mutableStateOf<GameSurfaceView?>(null) }
    // Use collectAsState to observe the StateFlow from the SurfaceView
    val score by gameSurfaceView.value?.scoreFlow?.collectAsState() ?: remember { mutableStateOf(0) }
    val gameState by gameSurfaceView.value?.gameStateFlow?.collectAsState() ?: remember { mutableStateOf(GameState.PLAYING) }
    // State for the slider value, controlled here in Compose
    var intensitySliderValue by remember { mutableStateOf(1f) }

    val lifecycleOwner = LocalLifecycleOwner.current
    // Manage the cleanup of the GameSurfaceView using DisposableEffect
    DisposableEffect(lifecycleOwner, gameSurfaceView.value) {
        val view = gameSurfaceView.value
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                Log.d("ComposeLifecycle", "ON_DESTROY -> Cleaning up GameSurfaceView")
                view?.cleanup()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            Log.d("ComposeLifecycle", "Dispose -> Removing lifecycle observer")
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Send intensity updates to the GameSurfaceView when the slider changes
    LaunchedEffect(intensitySliderValue, gameSurfaceView.value) {
        gameSurfaceView.value?.updateIntensity(intensitySliderValue)
    }

    // Box provides layering for SurfaceView and UI elements
    Box(modifier = Modifier.fillMaxSize()) {
        // AndroidView embeds the GameSurfaceView
        AndroidView(
            factory = { context ->
                GameSurfaceView(context).apply {
                    this.playBurstSound = playBurstSound
                    this.playMissSound = playMissSound
                    gameSurfaceView.value = this // Store reference to the created view
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Re-apply callbacks if the view instance somehow changes
                view.playBurstSound = playBurstSound
                view.playMissSound = playMissSound
            }
        )

        // UI Overlay Column
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 16.dp, bottom = 16.dp), // Padding for overlays
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween // Pushes elements apart
        ) {
            // Score Display
            Text(
                text = "Score: $score",
                fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )

            // Intensity Slider Row (Conditional)
            if (gameState == GameState.PLAYING) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp) // Padding outside row background
                        .background(Color.Black.copy(alpha = 0.5f), MaterialTheme.shapes.medium)
                        .padding(horizontal = 12.dp, vertical = 4.dp) // Padding inside row background
                ) {
                    Text("Speed:", color = Color.White, modifier = Modifier.padding(end = 8.dp))
                    Slider(
                        value = intensitySliderValue,
                        onValueChange = { intensitySliderValue = it }, // Update Compose state
                        valueRange = 0f..10f, steps = 9,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = intensitySliderValue.roundToInt().toString(),
                        color = Color.White,
                        modifier = Modifier.padding(start = 8.dp).width(30.dp) // Ensure space for "10"
                    )
                }
            } else {
                // Keep space at bottom when slider hidden
                Spacer(modifier = Modifier.height(50.dp).fillMaxWidth())
            }
        }

        // Game Over Overlay (drawn last if game state is GAME_OVER)
        if (gameState == GameState.GAME_OVER) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.75f)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth(0.8f).padding(32.dp)
                ) {
                    Text("Game Over!", fontSize = 36.sp, color = Color.Red, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Final Score: $score", fontSize = 28.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = {
                        // Reset game state via SurfaceView and reset slider here
                        gameSurfaceView.value?.resetGame()
                        intensitySliderValue = 1f
                    }) {
                        Text("Play Again", fontSize = 18.sp)
                    }
                }
            }
        }
    } // End Root Box
} // End GameScreenWithSurfaceView
