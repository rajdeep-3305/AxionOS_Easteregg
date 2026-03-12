package com.axion.os.easteregg

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
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.*
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

val ExpoOut = Easing { x -> if (x == 1f) 1f else 1f - 2.0.pow(-10.0 * x.toDouble()).toFloat() }
val BackEaseIn = Easing { x -> val s = 1.70158f; x * x * ((s + 1) * x - s) }

data class Star(val x: Float, val y: Float, val size: Float, val speed: Float, val alpha: Float)
data class Bullet(var position: Offset, val isEnemy: Boolean = false)
data class Particle(val pos: Offset, val vel: Offset, val color: Color, var life: Float, val size: Float = 4f)

@Composable
fun MainContainer() {
    var gameState by remember { mutableStateOf("ANIMATION") }
    
    Box(modifier = Modifier
        .fillMaxSize()
        .background(Color.Black)
        .statusBarsPadding()
    ) {
        val layer1 = remember { List(60) { Star(Random.nextFloat(), Random.nextFloat(), 1f, 0.04f, 0.15f) } }
        val layer2 = remember { List(40) { Star(Random.nextFloat(), Random.nextFloat(), 1.5f, 0.08f, 0.3f) } }
        val layer3 = remember { List(25) { Star(Random.nextFloat(), Random.nextFloat(), 2.2f, 0.15f, 0.5f) } }
        
        val starOffset by rememberInfiniteTransition().animateFloat(0f, 1f, infiniteRepeatable(tween(10000, easing = LinearEasing)))

        Canvas(modifier = Modifier.fillMaxSize()) {
            val drawStar: (Star, Float) -> Unit = { star, offset ->
                val y = (star.y + offset * star.speed) % 1f
                drawCircle(Color.White.copy(alpha = star.alpha), radius = star.size, center = Offset(star.x * size.width, y * size.height))
            }
            layer1.forEach { drawStar(it, starOffset) }
            layer2.forEach { drawStar(it, starOffset) }
            layer3.forEach { drawStar(it, starOffset) }
        }

        Crossfade(targetState = gameState, animationSpec = tween(1000), label = "state") { state ->
            when(state) {
                "ANIMATION" -> AtomCrashGame(onFinish = { gameState = "GAME" })
                "GAME" -> SpaceGame(onExit = { gameState = "ANIMATION" })
            }
        }
    }
}

@Composable
fun SpaceGame(onExit: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val screenWidth = constraints.maxWidth.toFloat()
        val screenHeight = constraints.maxHeight.toFloat()
        
        var shipPos by remember { mutableStateOf(Offset(screenWidth / 2, screenHeight * 0.85f)) }
        var shipLean by remember { mutableFloatStateOf(0f) }
        var bossPos by remember { mutableStateOf(Offset(screenWidth / 2, -200f)) }
        var bossHealth by remember { mutableStateOf(100f) }
        var gameStatus by remember { mutableStateOf("PLAYING") }
        
        val bullets = remember { mutableStateListOf<Bullet>() }
        val particles = remember { mutableStateListOf<Particle>() }
        var gameTime by remember { mutableLongStateOf(0L) }
        
        val destructionProgress = remember { Animatable(0f) }

        val animatedHealth by animateFloatAsState(
            targetValue = bossHealth / 100f,
            animationSpec = spring(stiffness = Spring.StiffnessLow), label = "health"
        )

        LaunchedEffect(Unit) {
            var startTime = -1L
            var lastBossShot = 0L
            while(gameStatus == "PLAYING") {
                withFrameMillis { frameTime ->
                    if (startTime == -1L) startTime = frameTime
                    gameTime = frameTime - startTime
                    
                    val loopDuration = 9000L
                    val progress = (gameTime % loopDuration).toFloat() / loopDuration
                    val yCycle = if (progress < 0.5f) progress * 2 else (1f - progress) * 2
                    bossPos = Offset(
                        (screenWidth / 2) + sin(gameTime / 600f) * (screenWidth * 0.32f),
                        screenHeight * 0.12f + yCycle * (screenHeight * 0.35f)
                    )

                    if (gameTime - lastBossShot > 1300L) {
                        bullets.add(Bullet(bossPos + Offset(0f, 100f), isEnemy = true))
                        lastBossShot = gameTime
                    }
                    
                    val bIterator = bullets.listIterator()
                    while(bIterator.hasNext()) {
                        val b = bIterator.next()
                        if (b.isEnemy) {
                            b.position = Offset(b.position.x, b.position.y + 14f)
                            if ((b.position - shipPos).getDistance() < 55f) {
                                gameStatus = "LOST"
                                launch { 
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    destructionProgress.animateTo(1f, tween(1000, easing = LinearOutSlowInEasing))
                                }
                            }
                        } else {
                            b.position = Offset(b.position.x, b.position.y - 48f)
                            if ((b.position - bossPos).getDistance() < 110f) {
                                bossHealth -= 3f
                                bIterator.remove()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                repeat(5) { particles.add(Particle(b.position, Offset(Random.nextFloat()*20-10, Random.nextFloat()*20-10), Color.White, 1f)) }
                                if (bossHealth <= 0) {
                                    bossHealth = 0f
                                    gameStatus = "WON"
                                    launch { 
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
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
                        p.life -= 0.05f
                        if (p.life <= 0) pIterator.remove()
                    }
                }
            }
            if (gameStatus != "PLAYING") {
                delay(3000)
                onExit()
            }
        }

        Box(modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        event.changes.forEach { change ->
                            if (gameStatus == "PLAYING") {
                                if (change.pressed && !change.previousPressed) {
                                    bullets.add(Bullet(Offset(shipPos.x, shipPos.y - 60f)))
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                                if (change.pressed) {
                                    val move = change.positionChange()
                                    shipLean = (move.x * 0.8f).coerceIn(-25f, 25f)
                                    shipPos = Offset(
                                        (shipPos.x + move.x).coerceIn(60f, screenWidth - 60f),
                                        (shipPos.y + move.y).coerceIn(200f, screenHeight - 120f)
                                    )
                                    change.consume()
                                } else { shipLean = 0f }
                            }
                        }
                    }
                }
            }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (gameStatus == "PLAYING" || gameStatus == "LOST" || (gameStatus == "WON" && destructionProgress.value < 1f)) {
                    val dAlpha = if (gameStatus == "WON") (1f - destructionProgress.value) else 1f
                    val dScale = if (gameStatus == "WON") (1f + destructionProgress.value * 0.5f) else 1f
                    val pulse = if (gameStatus == "PLAYING") sin(gameTime / 180f) * 0.1f else 0f
                    
                    drawCircle(Color.White.copy(0.12f * dAlpha), radius = 240f * (1f + pulse) * dScale, center = bossPos)
                    drawCircle(Color.White.copy(dAlpha), radius = 100f * dScale, center = bossPos, style = Stroke(2.5.dp.toPx()))
                    
                    rotate(gameTime / 8f, pivot = bossPos) {
                        drawArc(Color.White.copy(0.4f * dAlpha), 0f, 60f, false, bossPos - Offset(125f, 125f), Size(250f, 250f), style = Stroke(1.5.dp.toPx()))
                        drawArc(Color.White.copy(0.4f * dAlpha), 180f, 60f, false, bossPos - Offset(125f, 125f), Size(250f, 250f), style = Stroke(1.5.dp.toPx()))
                    }
                    val gRect = Rect(bossPos.x - 45f * dScale, bossPos.y - 45f * dScale, bossPos.x + 45f * dScale, bossPos.y + 45f * dScale)
                    drawArc(Color.White.copy(dAlpha), 35f, 290f, false, gRect.topLeft, gRect.size, style = Stroke(6.dp.toPx() * dAlpha.coerceAtLeast(0.1f)))
                    drawLine(Color.White.copy(dAlpha), bossPos, Offset(bossPos.x + 45f * dScale, bossPos.y), strokeWidth = 6.dp.toPx())
                }

                if (gameStatus == "PLAYING" || gameStatus == "WON" || (gameStatus == "LOST" && destructionProgress.value < 1f)) {
                    val dAlpha = if (gameStatus == "LOST") (1f - destructionProgress.value) else 1f
                    val dExpand = if (gameStatus == "LOST") destructionProgress.value * 100f else 0f
                    
                    rotate(shipLean, pivot = shipPos) {
                        val shipPath = Path().apply {
                            moveTo(shipPos.x, shipPos.y - 65f - dExpand); lineTo(shipPos.x - 45f - dExpand, shipPos.y + 40f + dExpand)
                            lineTo(shipPos.x, shipPos.y + 15f); lineTo(shipPos.x + 45f + dExpand, shipPos.y + 40f + dExpand); close()
                        }
                        drawPath(shipPath, Color.White.copy(dAlpha), style = Stroke(2.5.dp.toPx()))
                        drawCircle(Color.White.copy(0.2f * dAlpha), radius = 28f + dExpand, center = shipPos)
                    }
                }

                bullets.forEach { b -> 
                    if (b.isEnemy) {
                        drawCircle(Color.White, radius = 12f, center = b.position)
                        drawCircle(Color.White.copy(0.4f), radius = 24f, center = b.position)
                    } else {
                        drawRect(Color.White, b.position - Offset(2.5f, 25f), Size(5f, 50f))
                    }
                }
                particles.forEach { p -> drawCircle(p.color.copy(alpha = min(1f, p.life)), radius = p.size * p.life, center = p.pos) }
            }

            Column(modifier = Modifier.padding(28.dp).align(Alignment.TopStart)) {
                Text("AXION COMBAT", color = Color.White, style = TextStyle(letterSpacing = 8.sp, fontWeight = FontWeight.Black, fontSize = 16.sp))
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("ORB", color = Color.White.copy(0.6f), fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                    Box(modifier = Modifier.width(220.dp).height(10.dp).background(Color.White.copy(0.1f))) {
                        Box(modifier = Modifier.fillMaxHeight().fillMaxWidth(animatedHealth).background(Color.White))
                    }
                }
            }

            if (gameStatus == "PLAYING") {
                Text("MULTI-TOUCH PURGE PROTOCOL", modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp), color = Color.White.copy(0.25f), fontSize = 10.sp, letterSpacing = 4.sp)
            }
            
            if (gameStatus != "PLAYING" && destructionProgress.value > 0.6f) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(0.96f))) {
                    Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (gameStatus == "WON") "PURITY RESTORED" else "INTEGRITY BREACH",
                            color = Color.White, style = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraLight, letterSpacing = 12.sp, textAlign = TextAlign.Center)
                        )
                        Spacer(Modifier.height(20.dp))
                        Text(
                            text = if (gameStatus == "WON") "EXTERNAL INFLUENCE NEUTRALIZED" else "SYSTEM PURITY COMPROMISED",
                            color = Color.White.copy(0.4f), fontSize = 12.sp, letterSpacing = 3.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AtomCrashGame(onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    var remainingAtoms by remember { mutableIntStateOf(5) }
    var isExploding by remember { mutableStateOf(false) }
    var showFinalText by remember { mutableStateOf(false) }
    val crashProgress = remember { Animatable(0f) }
    val explosionProgress = remember { Animatable(0f) }
    val flashAlpha = remember { Animatable(0f) }
    val nucleusShake = remember { Animatable(0f) }
    val nucleusScale = remember { Animatable(1f) }
    val rotation by rememberInfiniteTransition().animateFloat(0f, 360f, infiniteRepeatable(tween(7000, easing = LinearEasing)))

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
                    explosionProgress.animateTo(1f, tween(1800, easing = ExpoOut))
                    showFinalText = true
                    delay(3500); onFinish()
                }
                crashProgress.snapTo(0f)
            }
        }
    }, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(450.dp).graphicsLayer { translationX = nucleusShake.value }) {
            val centerX = size.width / 2; val centerY = size.height / 2
            if (!showFinalText || isExploding) {
                val alpha = (1f - explosionProgress.value).coerceAtLeast(0f)
                if (alpha > 0f) {
                    for (i in 0 until 3) drawOvalOrbit(centerX, centerY, size.minDimension/2.8f, size.minDimension/2.8f*0.45f, i*60f, Color.White.copy(0.12f*alpha), 1.dp.toPx())
                }
                if (isExploding) drawCircle(Color.White.copy(0.4f*alpha), radius = explosionProgress.value * size.maxDimension, center = Offset(centerX, centerY), style = Stroke(1.dp.toPx()))
                val nRadius = (26.dp.toPx() * nucleusScale.value) + (if (isExploding) explosionProgress.value * 380.dp.toPx() else 0f)
                if (alpha > 0f) {
                    drawCircle(Color.White.copy(alpha), radius = nRadius, center = Offset(centerX, centerY))
                    drawCircle(Color.Black.copy(alpha), radius = nRadius*0.88f, center = Offset(centerX, centerY))
                    drawCircle(Color.White.copy(alpha*0.8f), radius = nRadius*0.35f, center = Offset(centerX, centerY))
                }
                if (!isExploding) {
                    val tilts = listOf(0f, 60f, 120f, 0f, 60f); val angles = listOf(0f, 72f, 144f, 216f, 288f)
                    for (i in 0 until remainingAtoms) {
                        drawElectronOnOrbit(centerX, centerY, size.minDimension/2.8f, size.minDimension/2.8f*0.45f, tilts[i], rotation+angles[i], 8.dp.toPx(), Color.White, if (i == remainingAtoms-1 && crashProgress.value > 0f) crashProgress.value else 0f)
                    }
                }
            }
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = flashAlpha.value)))
        
        AnimatedVisibility(visible = showFinalText, enter = fadeIn(tween(1800)) + scaleIn(tween(1800, easing = ExpoOut))) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 40.dp)) {
                Text(text = "AXION", style = TextStyle(fontSize = 52.sp, fontWeight = FontWeight.Thin, letterSpacing = 20.sp, color = Color.White), textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Text(text = "OPERATING SYSTEM", style = TextStyle(fontSize = 12.sp, color = Color.White.copy(0.5f), letterSpacing = 12.sp, fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
            }
        }
    }
}

private fun DrawScope.drawOvalOrbit(cX: Float, cY: Float, rX: Float, rY: Float, tilt: Float, color: Color, stroke: Float) {
    rotate(degrees = tilt, pivot = Offset(cX, cY)) { drawOval(color, Offset(cX - rX, cY - rY), Size(rX * 2, rY * 2), style = Stroke(stroke)) }
}

private fun DrawScope.drawElectronOnOrbit(cX: Float, cY: Float, rX: Float, rY: Float, tilt: Float, angle: Float, radius: Float, color: Color, crashProgress: Float) {
    val rad = Math.toRadians(angle.toDouble()); val tiltRad = Math.toRadians(tilt.toDouble())
    val x = rX * cos(rad).toFloat(); val y = rY * sin(rad).toFloat()
    val rotX = x * cos(tiltRad).toFloat() - y * sin(tiltRad).toFloat()
    val rotY = x * sin(tiltRad).toFloat() + y * cos(tiltRad).toFloat()
    val eX = cX + (rotX * (1f - crashProgress)); val eY = cY + (rotY * (1f - crashProgress))
    drawCircle(Color.White.copy(0.15f), radius = radius * 3f, center = Offset(eX, eY))
    drawCircle(color, radius, center = Offset(eX, eY), style = Stroke(1.5.dp.toPx()))
    drawCircle(Color.White, radius = radius * 0.4f, center = Offset(eX, eY))
}
