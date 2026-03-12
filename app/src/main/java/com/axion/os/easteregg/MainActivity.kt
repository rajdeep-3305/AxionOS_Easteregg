package com.axion.os.easteregg

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                MainContainer()
            }
        }
    }
}

val ExpoOut = Easing { x -> if (x == 1f) 1f else 1f - Math.pow(2.0, -10.0 * x.toDouble()).toFloat() }
val BackEaseIn = Easing { x -> val s = 1.70158f; x * x * ((s + 1) * x - s) }

data class Bullet(var position: Offset, val isEnemy: Boolean = false, val velocityX: Float = 0f)
data class Particle(var pos: Offset, val vel: Offset, val color: Color, var life: Float, val size: Float = 4f)
data class Shockwave(var radius: Float, var alpha: Float, val pos: Offset)
data class DamageText(val text: String, var pos: Offset, var life: Float) 
data class WarpLine(var x: Float, var y: Float, val speed: Float, val length: Float) 

data class GameStat(val timestamp: Long, val result: String, val time: String, val accuracy: Int)

class StatsManager(context: Context) {
    private val prefs = context.getSharedPreferences("axion_stats_v2", Context.MODE_PRIVATE)
    
    var history by mutableStateOf(getHistoryFromPrefs())
        private set
    var lifetimeWins by mutableIntStateOf(prefs.getInt("total_wins", 0))
        private set
    var lifetimeGames by mutableIntStateOf(prefs.getInt("total_games", 0))
        private set

    fun saveStat(stat: GameStat) {
        val currentHistory = getHistoryFromPrefs().toMutableList()
        currentHistory.add(0, stat)
        val limitedHistory = currentHistory.take(10)
        
        val array = JSONArray()
        limitedHistory.forEach {
            val obj = JSONObject()
            obj.put("ts", it.timestamp)
            obj.put("res", it.result)
            obj.put("time", it.time)
            obj.put("acc", it.accuracy)
            array.put(obj)
        }
        
        val newWins = prefs.getInt("total_wins", 0) + (if (stat.result == "WON") 1 else 0)
        val newGames = prefs.getInt("total_games", 0) + 1
        
        prefs.edit().putString("history", array.toString()).putInt("total_wins", newWins).putInt("total_games", newGames).apply()
            
        history = limitedHistory
        lifetimeWins = newWins
        lifetimeGames = newGames
    }
    
    private fun getHistoryFromPrefs(): List<GameStat> {
        val json = prefs.getString("history", null) ?: return emptyList()
        val list = mutableListOf<GameStat>()
        val array = JSONArray(json)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(GameStat(obj.getLong("ts"), obj.getString("res"), obj.getString("time"), obj.optInt("acc", 0)))
        }
        return list
    }
}

@Composable
fun MainContainer() {
    var gameState by remember { mutableStateOf("ANIMATION") }
    val context = LocalContext.current
    val statsManager = remember { StatsManager(context) }
    
    Box(modifier = Modifier.fillMaxSize().background(Color.Black).systemBarsPadding()) {
        Crossfade(targetState = gameState, animationSpec = tween(1000), label = "state") { state ->
            when(state) {
                "ANIMATION" -> AtomCrashGame(onFinish = { gameState = "GAME" })
                "GAME" -> SpaceGame(statsManager = statsManager, onExit = { gameState = "ANIMATION" })
            }
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            val scanlineSpacing = 8f
            for (y in 0..size.height.toInt() step scanlineSpacing.toInt()) {
                drawLine(color = Color.Black.copy(alpha = 0.25f), start = Offset(0f, y.toFloat()), end = Offset(size.width, y.toFloat()), strokeWidth = 2f)
            }
        }
    }
}

@Composable
fun SpaceGame(statsManager: StatsManager, onExit: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    val textMeasurer = rememberTextMeasurer()
    val scope = rememberCoroutineScope()
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        
        var shipPos by remember { mutableStateOf(Offset(screenWidth / 2, screenHeight * 0.85f)) }
        var shipLean by remember { mutableFloatStateOf(0f) }
        var isMoving by remember { mutableStateOf(false) } 
        var fireFlash by remember { mutableFloatStateOf(0f) }
        
        var bossPos by remember { mutableStateOf(Offset(screenWidth / 2, screenHeight * 0.18f)) }
        var bossPhase by remember { mutableFloatStateOf(0f) } 
        
        var bossHealth by remember { mutableFloatStateOf(100f) }
        var bossHitFlash by remember { mutableFloatStateOf(0f) } 
        
        var gameStatus by remember { mutableStateOf("PLAYING") } 
        
        val bullets = remember { mutableStateListOf<Bullet>() }
        val particles = remember { mutableStateListOf<Particle>() }
        val shockwaves = remember { mutableStateListOf<Shockwave>() } 
        val damageTexts = remember { mutableStateListOf<DamageText>() } 
        
        val warpLines = remember { 
            mutableStateListOf<WarpLine>().apply {
                repeat(25) { add(WarpLine(Random.nextFloat() * screenWidth, Random.nextFloat() * screenHeight, Random.nextFloat() * 15f + 10f, Random.nextFloat() * 80f + 20f)) }
            }
        }
        
        // SECRET PROTOCOL STATE
        var secretTaps by remember { mutableIntStateOf(0) }
        var overdriveMode by remember { mutableStateOf(false) }
        val shipAccentColor = if (overdriveMode) Color(0xFFFF007F) else Color.White
        val thrusterColor = if (overdriveMode) Color(0xFFFF007F) else Color(0xFF4DB6AC)
        
        var gameTime by remember { mutableLongStateOf(0L) }
        var lastFrameTime by remember { mutableLongStateOf(-1L) }
        
        val destructionProgress = remember { Animatable(0f) }
        val screenShake = remember { Animatable(0f) }
        
        var bulletsFired by remember { mutableIntStateOf(0) }
        var hitsConfirmed by remember { mutableIntStateOf(0) }
        val accuracy = derivedStateOf { if (bulletsFired > 0) ((hitsConfirmed.toFloat() / bulletsFired) * 100).toInt() else 0 }

        val animatedHealth by animateFloatAsState(targetValue = bossHealth / 100f, animationSpec = spring(stiffness = Spring.StiffnessLow), label = "health")
        
        val envColor by animateColorAsState(
            targetValue = if (bossHealth in 1f..25f) Color(0xFF8A2B2B) else Color.White,
            animationSpec = tween(1500), label = "env_color"
        )

        LaunchedEffect(Unit) {
            var lastBossShot = 0L
            while(gameStatus == "PLAYING") {
                withFrameMillis { frameTime ->
                    if (lastFrameTime == -1L) lastFrameTime = frameTime
                    val deltaMs = frameTime - lastFrameTime
                    lastFrameTime = frameTime
                    gameTime += deltaMs
                    
                    if (bossHitFlash > 0f) bossHitFlash = max(0f, bossHitFlash - 0.1f)
                    if (fireFlash > 0f) fireFlash = max(0f, fireFlash - 0.15f)

                    if (gameTime % 2L == 0L) {
                        val spread = if (isMoving) 15f else 5f
                        particles.add(Particle(pos = shipPos + Offset(Random.nextFloat() * spread - (spread/2), 40f), vel = Offset(0f, Random.nextFloat() * 5f + 5f), color = thrusterColor.copy(alpha = 0.6f), life = 1f, size = Random.nextFloat() * 3f + 1f))
                    }
                    
                    warpLines.forEach { w ->
                        w.y += w.speed
                        if (w.y > screenHeight + w.length) {
                            w.y = -w.length
                            w.x = Random.nextFloat() * screenWidth
                        }
                    }
                    
                    val targetSpeed = when {
                        bossHealth > 75f -> 1.3f  
                        bossHealth > 50f -> 1.7f
                        bossHealth > 25f -> 2.2f
                        else -> 2.8f              
                    }

                    bossPhase += (deltaMs / 1000f) * targetSpeed

                    bossPos = Offset(
                        x = (screenWidth / 2) + sin(bossPhase) * (screenWidth * 0.35f),
                        y = (screenHeight * 0.18f) + sin(bossPhase * 2.1f) * 15f 
                    )

                    val fireDelay = when {
                        bossHealth > 75f -> 1600L 
                        bossHealth > 50f -> 1400L 
                        bossHealth > 25f -> 1200L  
                        else -> 1300L 
                    }

                    if (gameTime - lastBossShot > fireDelay) {
                        if (bossHealth <= 25f) {
                            bullets.add(Bullet(bossPos + Offset(0f, 100f), isEnemy = true, velocityX = 0f))
                            bullets.add(Bullet(bossPos + Offset(-20f, 100f), isEnemy = true, velocityX = -4f)) 
                            bullets.add(Bullet(bossPos + Offset(20f, 100f), isEnemy = true, velocityX = 4f))
                        } else {
                            bullets.add(Bullet(bossPos + Offset(0f, 100f), isEnemy = true, velocityX = 0f))
                        }
                        lastBossShot = gameTime
                    }
                    
                    val bIterator = bullets.listIterator()
                    while(bIterator.hasNext()) {
                        val b = bIterator.next()
                        if (b.isEnemy) {
                            b.position = Offset(b.position.x + b.velocityX, b.position.y + 10f) 
                            if ((b.position - shipPos).getDistance() < 55f) {
                                gameStatus = "LOST"
                                launch { 
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    launch { 
                                        repeat(6) { screenShake.animateTo((15..25).random().toFloat(), tween(30)); screenShake.animateTo(-(15..25).random().toFloat(), tween(30)) }
                                        screenShake.animateTo(0f)
                                    }
                                    destructionProgress.animateTo(1f, tween(1000, easing = LinearOutSlowInEasing))
                                }
                            }
                        } else {
                            b.position = Offset(b.position.x, b.position.y - 48f)
                            if ((b.position - bossPos).getDistance() < 70f) {
                                bossHealth -= 3f
                                hitsConfirmed++
                                bossHitFlash = 1f 
                                shockwaves.add(Shockwave(radius = 60f, alpha = 0.8f, pos = b.position)) 
                                
                                damageTexts.add(DamageText("-0x03", bossPos + Offset(Random.nextFloat() * 60 - 30, Random.nextFloat() * 20 - 40), 1f))
                                
                                bIterator.remove()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) 
                                repeat(4) { particles.add(Particle(b.position, Offset(Random.nextFloat()*20-10, Random.nextFloat()*20-10), Color.White, 1f)) }
                                if (bossHealth <= 0) {
                                    bossHealth = 0f
                                    gameStatus = "WON"
                                    launch { 
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        launch { 
                                            repeat(8) { screenShake.animateTo((20..30).random().toFloat(), tween(30)); screenShake.animateTo(-(20..30).random().toFloat(), tween(30)) }
                                            screenShake.animateTo(0f)
                                        }
                                        destructionProgress.animateTo(1f, tween(1200, easing = LinearOutSlowInEasing))
                                    }
                                }
                                continue 
                            }
                        }
                        if (b.position.y < -150f || b.position.y > screenHeight + 150f) bIterator.remove()
                    }
                    
                    val pIterator = particles.listIterator()
                    while(pIterator.hasNext()) {
                        val p = pIterator.next()
                        p.pos += p.vel
                        p.life -= 0.05f
                        if (p.life <= 0) pIterator.remove()
                    }

                    val sIterator = shockwaves.listIterator()
                    while(sIterator.hasNext()) {
                        val s = sIterator.next()
                        s.radius += 6f
                        s.alpha -= 0.06f
                        if (s.alpha <= 0) sIterator.remove()
                    }
                    
                    val dtIterator = damageTexts.listIterator()
                    while(dtIterator.hasNext()) {
                        val dt = dtIterator.next()
                        dt.pos = Offset(dt.pos.x, dt.pos.y - 1.5f) 
                        dt.life -= 0.02f 
                        if (dt.life <= 0) dtIterator.remove()
                    }
                }
            }
            
            if (gameStatus != "PLAYING") {
                val centis = (gameTime % 1000) / 10
                val secs = (gameTime / 1000) % 60
                val mins = (gameTime / 60000)
                val timeStr = "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}:${centis.toString().padStart(2, '0')}"
                statsManager.saveStat(GameStat(System.currentTimeMillis(), gameStatus, timeStr, accuracy.value))
                delay(1000) 
                gameStatus = "LEADERBOARD"
            }
        }

        Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    event.changes.forEach { change ->
                        if (gameStatus == "PLAYING") {
                            if (change.pressed && !change.previousPressed) {
                                bullets.add(Bullet(Offset(shipPos.x, shipPos.y - 60f)))
                                bulletsFired++
                                fireFlash = 1f
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                            if (change.pressed) {
                                isMoving = true
                                val move = change.positionChange()
                                shipLean = (move.x * 0.8f).coerceIn(-25f, 25f)
                                shipPos = Offset((shipPos.x + move.x).coerceIn(60f, screenWidth - 60f), (shipPos.y + move.y).coerceIn(200f, screenHeight - 120f))
                                change.consume()
                            } else {
                                isMoving = false
                                shipLean = 0f
                            }
                        }
                    }
                }
            }
        }) {
            Canvas(modifier = Modifier.fillMaxSize().graphicsLayer { 
                translationX = screenShake.value 
                translationY = if (screenShake.value != 0f) (screenShake.value * 0.5f) else 0f
            }) {
                val parallaxX = (screenWidth / 2 - shipPos.x) * 0.04f
                val parallaxY = (screenHeight / 2 - shipPos.y) * 0.04f
                
                translate(left = parallaxX, top = parallaxY) {
                    val gridSpacing = 60f
                    val gridAlpha = if (bossHealth <= 25f) 0.25f else 0.15f
                    for (x in -100..size.width.toInt()+100 step gridSpacing.toInt()) {
                        for (y in -100..size.height.toInt()+100 step gridSpacing.toInt()) {
                            drawCircle(color = envColor.copy(gridAlpha), radius = 2f, center = Offset(x.toFloat(), y.toFloat()))
                        }
                    }
                }

                if (gameStatus == "PLAYING" || gameStatus == "WON") {
                    val streamColor = envColor.copy(alpha = 0.1f)
                    warpLines.forEach { w ->
                        drawLine(color = streamColor, start = Offset(w.x, w.y), end = Offset(w.x, w.y + w.length), strokeWidth = 2f, cap = StrokeCap.Round)
                    }
                }

                shockwaves.forEach { s ->
                    drawCircle(color = Color.White.copy(alpha = s.alpha), radius = s.radius, center = s.pos, style = Stroke(2f))
                }

                // --- BOSS DRAWING ---
                if (gameStatus == "PLAYING" || gameStatus == "LOST" || (gameStatus == "WON" && destructionProgress.value < 1f)) {
                    val dAlpha = if (gameStatus == "WON") (1f - destructionProgress.value) else 1f
                    val dScale = if (gameStatus == "WON") (1f + destructionProgress.value * 0.5f) else 1f
                    val r = 50f * dScale
                    
                    if (bossHitFlash > 0f) {
                        val glitchOffset = Offset((Random.nextFloat() - 0.5f) * 15f, (Random.nextFloat() - 0.5f) * 15f)
                        drawCircle(Color.Cyan.copy(alpha = 0.4f * bossHitFlash * dAlpha), radius = r, center = bossPos + glitchOffset, style = Stroke(4f))
                        drawCircle(Color.Red.copy(alpha = 0.4f * bossHitFlash * dAlpha), radius = r, center = bossPos - glitchOffset, style = Stroke(4f))
                    }
                    
                    val bossPath = Path().apply { addOval(Rect(bossPos - Offset(r, r), Size(r * 2, r * 2))) }
                    
                    clipPath(bossPath) {
                        drawRect(Color(0xFFA08A38).copy(alpha = dAlpha), topLeft = Offset(bossPos.x - r, bossPos.y - r), size = Size(r * 2, r * 2))
                        drawRect(Color(0xFF8A4B38).copy(alpha = dAlpha), topLeft = Offset(bossPos.x - r * 0.6f, bossPos.y - r), size = Size(r * 1.2f, r * 2))
                        drawRect(Color(0xFF8A2B2B).copy(alpha = dAlpha), topLeft = Offset(bossPos.x - r * 0.2f, bossPos.y - r), size = Size(r * 0.4f, r * 2))
                    }
                    
                    drawCircle(color = Color(0xFFE6A04D).copy(alpha = dAlpha), radius = r, center = bossPos, style = Stroke(3f))
                    
                    rotate(gameTime * 0.05f, bossPos) {
                        val arcRadius = r + 10f
                        drawArc(color = Color(0xFF2E86C1).copy(alpha = dAlpha), startAngle = 135f, sweepAngle = 90f, useCenter = false, topLeft = Offset(bossPos.x - arcRadius, bossPos.y - arcRadius), size = Size(arcRadius * 2, arcRadius * 2), style = Stroke(width = 4f, cap = StrokeCap.Round))
                        drawArc(color = Color(0xFF2E86C1).copy(alpha = dAlpha), startAngle = -45f, sweepAngle = 90f, useCenter = false, topLeft = Offset(bossPos.x - arcRadius, bossPos.y - arcRadius), size = Size(arcRadius * 2, arcRadius * 2), style = Stroke(width = 4f, cap = StrokeCap.Round))
                    }

                    if (bossHitFlash > 0f) {
                        drawCircle(color = Color.White.copy(alpha = bossHitFlash * dAlpha), radius = r + 15f, center = bossPos)
                    }

                    val textLayoutResult = textMeasurer.measure(text = "G", style = TextStyle(color = Color.White.copy(dAlpha), fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace))
                    drawText(textLayoutResult = textLayoutResult, topLeft = Offset(bossPos.x - textLayoutResult.size.width / 2, bossPos.y - textLayoutResult.size.height / 2))
                    
                    val coordsText = textMeasurer.measure(text = "X: ${bossPos.x.toInt().toString().padStart(4, '0')}\nY: ${bossPos.y.toInt().toString().padStart(4, '0')}", style = TextStyle(color = envColor.copy(0.4f * dAlpha), fontSize = 10.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center))
                    drawText(textLayoutResult = coordsText, topLeft = Offset(bossPos.x - coordsText.size.width / 2, bossPos.y - 120f))
                }

                // --- SHIP DRAWING ---
                if (gameStatus == "PLAYING" || gameStatus == "WON" || (gameStatus == "LOST" && destructionProgress.value < 1f)) {
                    val dAlpha = if (gameStatus == "LOST") (1f - destructionProgress.value) else 1f
                    val dExpand = if (gameStatus == "LOST") destructionProgress.value * 100f else 0f
                    
                    rotate(shipLean, pivot = shipPos) {
                        val chevronPath = Path().apply {
                            moveTo(shipPos.x, shipPos.y - 45f - dExpand) 
                            lineTo(shipPos.x + 30f, shipPos.y + 20f + dExpand) 
                            lineTo(shipPos.x, shipPos.y + 5f + dExpand) 
                            lineTo(shipPos.x - 30f, shipPos.y + 20f + dExpand) 
                            close()
                        }
                        drawPath(path = chevronPath, color = shipAccentColor.copy(dAlpha), style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        
                        if (fireFlash > 0f) {
                            drawCircle(color = thrusterColor.copy(alpha = fireFlash * dAlpha), radius = 12f * fireFlash, center = shipPos - Offset(0f, 45f))
                        }
                    }
                    
                    val coordsText = textMeasurer.measure(text = "X: ${shipPos.x.toInt().toString().padStart(4, '0')}\nY: ${shipPos.y.toInt().toString().padStart(4, '0')}", style = TextStyle(color = Color.White.copy(0.4f * dAlpha), fontSize = 10.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center))
                    drawText(textLayoutResult = coordsText, topLeft = Offset(shipPos.x - coordsText.size.width / 2, shipPos.y + 40f))
                }
                
                damageTexts.forEach { dt ->
                    val layout = textMeasurer.measure(dt.text, style = TextStyle(color = Color(0xFFE6A04D).copy(alpha = dt.life), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold))
                    drawText(layout, topLeft = dt.pos)
                }

                val damageColors = listOf(Color(0xFF2E86C1), Color(0xFFA08A38), Color(0xFF8A4B38), Color(0xFF8A2B2B)) 
                bullets.forEach { b -> 
                    if (b.isEnemy) {
                        val color = damageColors[bullets.indexOf(b) % damageColors.size]
                        drawCircle(color, radius = 6f, center = b.position) 
                    } else { 
                        drawLine(color = shipAccentColor, start = b.position, end = Offset(b.position.x, b.position.y - 25f), strokeWidth = 4f, cap = StrokeCap.Round)
                    }
                }
                particles.forEach { p -> drawCircle(p.color.copy(alpha = Math.max(0f, p.life)), radius = p.size * p.life, center = p.pos) }
            }

            // --- HUD ---
            val formatCentis = (gameTime % 1000) / 10
            val formatSecs = (gameTime / 1000) % 60
            val formatMins = (gameTime / 60000)
            val timeStr = "${formatMins.toString().padStart(2, '0')}:${formatSecs.toString().padStart(2, '0')}:${formatCentis.toString().padStart(2, '0')}"
            
            val missionStartTime: String = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()) }

            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp).align(Alignment.TopStart), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(
                        text = if (overdriveMode) "OVERDRIVE PROTOCOL" else "AXION COMBAT", 
                        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            secretTaps++
                            if (secretTaps == 5) {
                                overdriveMode = !overdriveMode
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        color = shipAccentColor, 
                        style = TextStyle(letterSpacing = 6.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = 16.sp)
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("ORB", color = Color.White.copy(0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(end = 12.dp))
                        Box(modifier = Modifier.width(160.dp).height(6.dp).background(Color.White.copy(0.1f))) {
                            Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedHealth).background(Color(0xFF4DB6AC))) 
                        }
                        Spacer(Modifier.width(12.dp))
                        Text("${(bossHealth * 20.26f).toInt()}/2026", color = Color.White.copy(0.5f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("$missionStartTime > MISSION START", color = Color(0xFFE6A04D), fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "T+ $timeStr", color = Color.White, style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp))
                    Text(text = "ACC: ${accuracy.value}%", color = Color.White.copy(0.8f), style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Normal, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp))
                }
            }

            if (gameStatus == "PLAYING") {
                Text("TOUCH TO MOVE • TAP OTHER FINGER TO FIRE", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp), color = Color.White.copy(0.3f), fontSize = 10.sp, fontFamily = FontFamily.Monospace, letterSpacing = 2.sp)
            }
            
            if (gameStatus == "LEADERBOARD") {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.96f))) {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = if (gameStatus == "WON") "PURITY RESTORED" else "INTEGRITY BREACH", color = Color.White, style = TextStyle(fontSize = 30.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Light, letterSpacing = 8.sp, textAlign = TextAlign.Center))
                        Spacer(Modifier.height(8.dp))
                        Text(text = if (gameStatus == "WON") "EXTERNAL INFLUENCE NEUTRALIZED" else "SYSTEM PURITY COMPROMISED", color = Color.White.copy(0.4f), fontFamily = FontFamily.Monospace, fontSize = 11.sp, letterSpacing = 3.sp)
                        Spacer(Modifier.height(32.dp))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                             Text("FINAL ACCURACY", color = Color.White.copy(0.5f), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 4.sp)
                             Text("${accuracy.value}%", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 28.sp, fontWeight = FontWeight.Light)
                        }
                        Spacer(Modifier.height(32.dp))
                        Text("MISSION LOG // TOP OPERATIONS", color = Color.White.copy(0.3f), fontFamily = FontFamily.Monospace, fontSize = 10.sp, letterSpacing = 4.sp)
                        Spacer(Modifier.height(16.dp))
                        Box(modifier = Modifier.height(180.dp).width(320.dp)) {
                            LazyColumn(horizontalAlignment = Alignment.CenterHorizontally) {
                                items(statsManager.history.take(10)) { stat ->
                                    val date = SimpleDateFormat("MMM dd HH:mm", Locale.getDefault()).format(Date(stat.timestamp))
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = date, color = Color.White.copy(0.4f), fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.width(90.dp))
                                        Text(text = stat.time, color = Color.White.copy(0.7f), fontFamily = FontFamily.Monospace, fontSize = 10.sp, textAlign = TextAlign.Center, modifier = Modifier.width(60.dp))
                                        Text(text = if (stat.result == "WON") "PURGED" else "FAILED", color = if (stat.result == "WON") Color.White else Color.Gray, fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(60.dp))
                                        Text(text = "${stat.accuracy}% ACC", color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, textAlign = TextAlign.End, modifier = Modifier.width(60.dp))
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(48.dp))
                        Text("TAP TO REINITIALIZE >", modifier = Modifier.clickable { scope.launch { destructionProgress.snapTo(0f) }; onExit() }, color = Color(0xFFE6A04D), fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AtomCrashGame(onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    
    var remainingAtoms by remember { mutableIntStateOf(5) }
    var isExploding by remember { mutableStateOf(false) }
    var showFinalText by remember { mutableStateOf(false) }
    
    var currentWordIndex by remember { mutableIntStateOf(0) }
    val animatedWords = listOf("faster.", "more powerful.", "more reliable.", "axion.")
    
    val crashProgress = remember { Animatable(0f) }
    val explosionProgress = remember { Animatable(0f) }
    val flashAlpha = remember { Animatable(0f) }
    val nucleusShake = remember { Animatable(0f) }
    val nucleusScale = remember { Animatable(1f) }
    
    val slowGlobalRotation by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(25000, easing = LinearEasing)))
    val corePulse by rememberInfiniteTransition().animateFloat(0.8f, 1.4f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val rotation by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)))

    LaunchedEffect(showFinalText) {
        if (showFinalText) {
            delay(1200)
            currentWordIndex = 1 
            delay(1200)
            currentWordIndex = 2 
            delay(1200)
            currentWordIndex = 3 
            delay(2500) 
            onFinish()
        }
    }

    Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
        if (remainingAtoms > 0 && !crashProgress.isRunning && !isExploding) {
            scope.launch {
                launch { haptic.performHapticFeedback(HapticFeedbackType.LongPress); nucleusScale.animateTo(1.25f, tween(60)); nucleusScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy)) }
                launch { repeat(3) { nucleusShake.animateTo(8f, tween(30)); nucleusShake.animateTo(-8f, tween(30)) }; nucleusShake.animateTo(0f, tween(30)) }
                
                crashProgress.animateTo(1f, tween(250, easing = BackEaseIn))
                remainingAtoms--
                
                if (remainingAtoms == 0) {
                    isExploding = true
                    launch { flashAlpha.animateTo(0.5f, tween(40)); flashAlpha.animateTo(0f, tween(1200)) }
                    explosionProgress.animateTo(1f, tween(1500, easing = ExpoOut))
                    showFinalText = true
                }
                crashProgress.snapTo(0f)
            }
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(450.dp).graphicsLayer { translationX = nucleusShake.value }) {
            val centerX = size.width / 2
            val centerY = size.height / 2
            
            if (!showFinalText || isExploding) {
                val alpha = (1f - explosionProgress.value).coerceAtLeast(0f)
                
                rotate(slowGlobalRotation, Offset(centerX, centerY)) {
                    if (alpha > 0f) {
                        for (i in 0 until 3) {
                            DrawOvalOrbit(centerX, centerY, size.minDimension/2.8f, size.minDimension/2.8f*0.45f, i*60f, Color.White.copy(0.15f*alpha), 1.5f.dp.toPx())
                        }
                    }
                    
                    val nRadius = (26.dp.toPx() * nucleusScale.value) + (if (isExploding) explosionProgress.value * 380.dp.toPx() else 0f)
                    
                    if (alpha > 0f) {
                        drawCircle(Color(0xFF4DB6AC).copy(alpha = 0.2f * alpha), radius = nRadius * 1.5f * corePulse, center = Offset(centerX, centerY))
                        drawCircle(Color.White.copy(alpha), radius = nRadius, center = Offset(centerX, centerY))
                        drawCircle(Color.Black.copy(alpha), radius = nRadius*0.88f, center = Offset(centerX, centerY))
                        drawCircle(Color.White.copy(alpha*0.8f), radius = nRadius*0.35f, center = Offset(centerX, centerY))
                    }
                    
                    if (!isExploding) {
                        val tilts = listOf(0f, 60f, 120f, 0f, 60f)
                        val angles = listOf(0f, 72f, 144f, 216f, 288f)
                        for (i in 0 until remainingAtoms) {
                            DrawElectronOnOrbit(centerX, centerY, size.minDimension/2.8f, size.minDimension/2.8f*0.45f, tilts[i], rotation+angles[i], 8.dp.toPx(), Color.White, if (i == remainingAtoms-1 && crashProgress.value > 0f) crashProgress.value else 0f)
                        }
                    }
                }
                
                if (isExploding) {
                    drawCircle(Color.White.copy(0.4f*alpha), radius = explosionProgress.value * size.maxDimension, center = Offset(centerX, centerY), style = Stroke(1.dp.toPx()))
                }
            }
        }
        
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = flashAlpha.value)))
        
        AnimatedVisibility(visible = showFinalText, enter = fadeIn(tween(1800))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 40.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "Axion", style = TextStyle(fontSize = 58.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White))
                    Text(text = "OS", style = TextStyle(fontSize = 58.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color.White, drawStyle = Stroke(width = 3f)))
                }

                Spacer(Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(24.dp)) {
                    Text("Make your android ", style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Color.White.copy(0.6f)))
                    
                    AnimatedContent(
                        targetState = currentWordIndex,
                        transitionSpec = {
                            if (targetState > initialState) {
                                (slideInVertically { height -> height } + fadeIn(tween(400))).togetherWith(
                                    slideOutVertically { height -> -height } + fadeOut(tween(400))
                                )
                            } else {
                                fadeIn() togetherWith fadeOut()
                            }
                        },
                        label = "word_carousel"
                    ) { index ->
                        val textColor = if (index == 3) Color(0xFF4DB6AC) else Color.White.copy(0.6f)
                        Text(text = animatedWords[index], style = TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = textColor))
                    }
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.DrawOvalOrbit(cX: Float, cY: Float, rX: Float, rY: Float, tilt: Float, color: Color, stroke: Float) {
    rotate(degrees = tilt, pivot = Offset(cX, cY)) { drawOval(color, Offset(cX - rX, cY - rY), Size(rX * 2, rY * 2), style = Stroke(stroke)) }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.DrawElectronOnOrbit(cX: Float, cY: Float, rX: Float, rY: Float, tilt: Float, angle: Float, radius: Float, color: Color, crashProgress: Float) {
    val rad = Math.toRadians(angle.toDouble())
    val tiltRad = Math.toRadians(tilt.toDouble())
    val x = rX * cos(rad).toFloat()
    val y = rY * sin(rad).toFloat()
    val rotX = x * cos(tiltRad).toFloat() - y * sin(tiltRad).toFloat()
    val rotY = x * sin(tiltRad).toFloat() + y * cos(tiltRad).toFloat()
    val eX = cX + (rotX * (1f - crashProgress))
    val eY = cY + (rotY * (1f - crashProgress))
    
    drawCircle(Color.White.copy(0.2f), radius = radius * 2.5f, center = Offset(eX, eY))
    drawCircle(color, radius, center = Offset(eX, eY))
}
