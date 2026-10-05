package com.sensinglocal.blackjack

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas as ComposeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// Chunky "pixel" size of the street scene: it's drawn on a grid this many cells wide, each
// cell scaled up to a solid block, so it reads as low-res pixel art on the 480px display.
private const val GRID_COLS = 60
private const val TITLE = "BLACKJACK"
private const val TITLE_LETTER_DELAY_MS = 500L
private const val RAIN_DROP_COUNT = 48
private const val RAIN_PERIOD_MS = 1400

private val SkyBands = listOf(
    Color(0xFF05060A), Color(0xFF0A0D14), Color(0xFF111722), Color(0xFF1A2230)
)
private val MudBase = Color(0xFF3A2A1B)
private val MudDark = Color(0xFF2F2216)
private val MudLight = Color(0xFF45321F)
private val PuddleWater = Color(0xFF34424F)
private val PuddleGlint = Color(0xFF5B7085)
private val RainColor = Color(0xFF8FA6BF)
private val LampLight = Color(0xFFFFE08A)

/**
 * Story Mode's opening cinematic, played right after picking a slot on New Game: the screen
 * fades to black, "BLACKJACK" types in one letter at a time, fades out, "CASINOPOLIS. / YEAR
 * UNKNOWN." fades in, then the camera pans down from the black sky to a rainy mud street
 * (all pixel art). With [playIntro] false (Resume) it skips straight to that final street
 * frame. A back button appears once the scene has settled.
 */
@Composable
fun StoryIntroScreen(playIntro: Boolean, onSettled: () -> Unit, onExit: () -> Unit) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.roundToPx() }
        val heightPx = with(density) { maxHeight.roundToPx() }
        val cell = (widthPx / GRID_COLS).coerceAtLeast(1)
        val cols = (widthPx + cell - 1) / cell
        val rows = (heightPx + cell - 1) / cell

        // The street is baked once into a bitmap two screens tall (black sky above, street
        // below) off the main thread, then just slid past the viewport — same "bake once,
        // drawImage per frame" approach as the menu/cards, so the pan is a cheap translate.
        val world by produceState<ImageBitmap?>(null, widthPx, heightPx) {
            value = withContext(Dispatchers.Default) { renderStreetWorld(cols, rows, cell, density) }
        }

        val blackAlpha = remember { Animatable(0f) }
        var menuBackdrop by remember { mutableStateOf(playIntro) }
        var revealed by remember { mutableStateOf(0) }
        val titleAlpha = remember { Animatable(1f) }
        val line1Alpha = remember { Animatable(0f) }
        val line2Alpha = remember { Animatable(0f) }
        val pan = remember { Animatable(if (playIntro) 0f else 1f) }
        val backAlpha = remember { Animatable(0f) }
        var showBack by remember { mutableStateOf(false) }
        val panStarted by remember { derivedStateOf { pan.value > 0f } }

        LaunchedEffect(Unit) {
            if (playIntro) {
                blackAlpha.animateTo(1f, tween(1800))
                menuBackdrop = false
                delay(400)
                for (i in 1..TITLE.length) {
                    delay(TITLE_LETTER_DELAY_MS)
                    revealed = i
                }
                delay(1200)
                titleAlpha.animateTo(0f, tween(800))
                delay(300)
                line1Alpha.animateTo(1f, tween(900))
                delay(500)
                line2Alpha.animateTo(1f, tween(900))
                delay(1800)
                pan.animateTo(1f, tween(6000, easing = FastOutSlowInEasing))
            }
            showBack = true
            onSettled()
            backAlpha.animateTo(1f, tween(800))
        }

        // The menu is what the black fades over, so it's only needed (frozen, no animation)
        // until the fade completes.
        if (menuBackdrop) {
            MainMenuScreen(
                onResume = {}, onNewGame = {}, onQuickPlay = {},
                onTutorial = {}, onSettings = {},
                isActive = false
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = blackAlpha.value }
                    .background(Color.Black)
            )
        }

        if (panStarted) {
            world?.let { bitmap ->
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawImage(bitmap, topLeft = Offset(0f, -pan.value * rows * cell))
                }
            }
            RainOverlay(
                cols = cols,
                rows = rows,
                cell = cell,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = pan.value }
            )
        }

        val textMeasurer = rememberTextMeasurer()
        val titleSize = remember(widthPx) { fitFontSize(textMeasurer, TITLE, widthPx * 0.94f) }
        val line1Size = remember(widthPx) { fitFontSize(textMeasurer, "CASINOPOLIS.", widthPx * 0.9f) }
        val line2Size = remember(widthPx) { fitFontSize(textMeasurer, "YEAR UNKNOWN.", widthPx * 0.78f) }

        // Letters not yet revealed are drawn transparent rather than left out, so the word
        // keeps its final layout the whole time and each letter just appears in place.
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = titleAlpha.value },
            contentAlignment = Alignment.Center
        ) {
            TitleText(revealed, MenuRedDark, titleSize, Modifier.offset(4.dp, 4.dp))
            TitleText(revealed, MenuRed, titleSize, Modifier)
        }

        // Lives in the world, not the screen: it rides up out of view as the camera pans down.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, (-pan.value * rows * cell).toInt()) },
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "CASINOPOLIS.",
                color = MenuGold,
                fontSize = line1Size,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer { alpha = line1Alpha.value }
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "YEAR UNKNOWN.",
                color = Color(0xFFBBBBBB),
                fontSize = line2Size,
                softWrap = false,
                maxLines = 1,
                modifier = Modifier
                    .wrapContentWidth(unbounded = true)
                    .graphicsLayer { alpha = line2Alpha.value }
            )
        }

        if (showBack) {
            // Inset scaled to the screen so the button clears the round bezel — same
            // reasoning as ScrollableInfoScreen's back button.
            val cornerInset = minOf(maxWidth, maxHeight) * 0.18f
            BackButton(
                onClick = onExit,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = cornerInset, top = cornerInset)
                    .graphicsLayer { alpha = backAlpha.value }
            )
        }
    }
}

@Composable
private fun TitleText(revealed: Int, color: Color, size: TextUnit, modifier: Modifier) {
    val text = remember(revealed, color) {
        buildAnnotatedString {
            TITLE.forEachIndexed { i, ch ->
                withStyle(SpanStyle(color = if (i < revealed) color else Color.Transparent)) { append(ch) }
            }
        }
    }
    Text(
        text = text,
        fontSize = size,
        softWrap = false,
        maxLines = 1,
        modifier = modifier.wrapContentWidth(unbounded = true)
    )
}

/** Font size (in sp) at which [text] in VT323 comes out [targetPx] wide. Text width scales
 * linearly with font size, so measure once at a probe size and scale. */
private fun fitFontSize(measurer: TextMeasurer, text: String, targetPx: Float): TextUnit {
    val probe = 100f
    val probeWidth = measurer.measure(text, TextStyle(fontFamily = VT323, fontSize = probe.sp)).size.width
    return (probe * targetPx / probeWidth).sp
}

private class RainDrop(val col: Int, val phase: Float, val speed: Int, val length: Int)

/** Falling pixel-streak rain. Only composed once the pan has started so the infinite
 * transition isn't waking the CPU during the black-screen title sequence. */
@Composable
private fun RainOverlay(cols: Int, rows: Int, cell: Int, modifier: Modifier) {
    val drops = remember(cols) {
        val rnd = Random(11)
        List(RAIN_DROP_COUNT) {
            RainDrop(rnd.nextInt(cols), rnd.nextFloat(), 1 + rnd.nextInt(3), 2 + rnd.nextInt(2))
        }
    }
    val t by rememberInfiniteTransition(label = "rain").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(RAIN_PERIOD_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "rainT"
    )
    Canvas(modifier = modifier) {
        val span = rows + 6
        for (d in drops) {
            // Integer speeds keep the loop seamless at the wrap point.
            val y = ((d.phase + t * d.speed) % 1f) * span - 3
            drawRect(
                color = RainColor.copy(alpha = 0.55f),
                topLeft = Offset(d.col * cell.toFloat(), y * cell),
                size = Size(cell.toFloat(), d.length * cell.toFloat())
            )
        }
    }
}

/** Bakes the whole street world: [rows] tall of black sky, then [rows] of dim sky bands,
 * building silhouettes, a lamp post, and mud with ruts and puddles. Everything is whole
 * [cell]-sized blocks, seeded so it looks the same every time. */
private fun renderStreetWorld(cols: Int, rows: Int, cell: Int, density: Density): ImageBitmap {
    val w = cols * cell
    val h = 2 * rows * cell
    val bitmap = ImageBitmap(w, h)
    val rnd = Random(42)
    CanvasDrawScope().draw(density, LayoutDirection.Ltr, ComposeCanvas(bitmap), Size(w.toFloat(), h.toFloat())) {
        fun block(x: Int, y: Int, wc: Int, hc: Int, color: Color) {
            if (wc <= 0 || hc <= 0) return
            drawRect(
                color = color,
                topLeft = Offset(x * cell.toFloat(), y * cell.toFloat()),
                size = Size(wc * cell.toFloat(), hc * cell.toFloat())
            )
        }

        block(0, 0, cols, 2 * rows, Color.Black)

        // Where the buildings meet the street, in world rows.
        val horizon = rows + (rows * 0.52f).toInt()

        // Sky bands, lightening toward the horizon, with ragged 0-2 row bumps at each edge.
        val bandHeight = (horizon - rows) / SkyBands.size
        SkyBands.forEachIndexed { i, color ->
            val top = rows + i * bandHeight
            val bottom = if (i == SkyBands.lastIndex) horizon else top + bandHeight
            block(0, top, cols, bottom - top, color)
            if (i > 0) {
                var x = 0
                while (x < cols) {
                    val bump = rnd.nextInt(3)
                    block(x, top - bump, 3, bump, color)
                    x += 3
                }
            }
        }

        // Building silhouettes with a few lit windows.
        val wallColors = listOf(Color(0xFF0B0D12), Color(0xFF10131A))
        var x = 0
        while (x < cols) {
            val bw = 7 + rnd.nextInt(6)
            val bh = 14 + rnd.nextInt(horizon - rows - 18)
            val top = horizon - bh
            block(x, top, bw, bh, wallColors[rnd.nextInt(wallColors.size)])
            if (rnd.nextInt(3) == 0) block(x + 1 + rnd.nextInt(bw - 3), top - 3, 2, 3, wallColors[0])
            var wy = top + 2
            while (wy + 2 < horizon - 1) {
                var wx = x + 1
                while (wx + 2 <= x + bw - 1) {
                    val lit = rnd.nextFloat() < 0.22f
                    block(
                        wx, wy, 2, 2,
                        if (lit) (if (rnd.nextBoolean()) Color(0xFFC8923A) else Color(0xFFE0B85A))
                        else Color(0xFF141824)
                    )
                    wx += 3
                }
                wy += 4
            }
            x += bw + rnd.nextInt(2)
        }

        // Curb, then mud with speckled patches and two wheel ruts.
        block(0, horizon, cols, 2, Color(0xFF1C1712))
        val mudTop = horizon + 2
        val bottom = 2 * rows
        block(0, mudTop, cols, bottom - mudTop, MudBase)
        for (y in mudTop until bottom) {
            var cx = 0
            while (cx < cols) {
                val r = rnd.nextFloat()
                if (r < 0.18f) block(cx, y, 2, 1, MudDark) else if (r < 0.32f) block(cx, y, 2, 1, MudLight)
                cx += 2
            }
        }
        for (rutRow in listOf(mudTop + (rows * 0.2f).toInt(), mudTop + (rows * 0.32f).toInt())) {
            var cx = 0
            while (cx < cols) {
                val len = 4 + rnd.nextInt(6)
                if (rnd.nextFloat() < 0.8f) block(cx, rutRow + rnd.nextInt(2), len, 1, MudDark)
                cx += len + rnd.nextInt(3)
            }
        }

        // Puddles: flattened ellipses of dull water with a couple of glints.
        val puddles = listOf(
            Triple(cols * 0.30f, mudTop + (rows * 0.12f), 7), Triple(cols * 0.72f, mudTop + (rows * 0.17f), 9),
            Triple(cols * 0.45f, mudTop + (rows * 0.30f), 8), Triple(cols * 0.85f, mudTop + (rows * 0.36f), 6),
            Triple(cols * 0.15f, mudTop + (rows * 0.38f), 7)
        )
        for ((pcx, pcy, rx) in puddles) {
            val ry = 2
            for (dy in -ry..ry) {
                val half = (rx * sqrt(1f - (dy * dy) / ((ry + 1f) * (ry + 1f)))).toInt()
                block(pcx.toInt() - half, pcy.toInt() + dy, half * 2, 1, PuddleWater)
            }
            block(pcx.toInt() - rx / 2, pcy.toInt() - 1, 2, 1, PuddleGlint)
            block(pcx.toInt() + rx / 3, pcy.toInt(), 1, 1, PuddleGlint)
        }

        // Lamp post on the curb with a soft glow and a pool of light on the mud.
        val lampX = (cols * 0.18f).toInt()
        val lampTop = horizon - 18
        block(lampX - 4, lampTop - 3, 9, 7, Color(0x14FFD27A))
        block(lampX - 2, lampTop - 1, 5, 4, Color(0x22FFD27A))
        block(lampX, lampTop + 2, 1, 20, Color(0xFF1A1A1F))
        block(lampX - 2, lampTop, 5, 1, Color(0xFF1A1A1F))
        block(lampX - 1, lampTop + 1, 3, 1, LampLight)
        block(lampX - 6, mudTop + 2, 13, 3, Color(0x22FFD27A))
        block(lampX - 3, mudTop + 1, 7, 1, Color(0x22FFD27A))
    }
    return bitmap
}
