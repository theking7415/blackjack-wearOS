package com.sensinglocal.blackjack

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.material.Text
import com.sensinglocal.blackjack.game.Card
import com.sensinglocal.blackjack.game.Rank
import com.sensinglocal.blackjack.game.Suit
import kotlinx.coroutines.launch

/**
 * Shared scaffold for every secondary/full-page screen (tutorial, settings, story placeholder):
 * a scrollable, rotary-scrollable ScalingLazyColumn on a themed background, plus a fixed
 * top-left [BackButton]. Mirrors the rotary/focus pattern already proven on the game screen
 * (BlackjackScreen in MainActivity.kt) rather than reinventing it per-screen.
 */
@OptIn(androidx.wear.compose.foundation.ExperimentalWearFoundationApi::class)
@Composable
private fun ScrollableInfoScreen(
    onBack: () -> Unit,
    background: Color = MenuRedDark,
    content: ScalingLazyListScope.() -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(background)) {
        val focusRequester = rememberActiveFocusRequester()
        val coroutineScope = rememberCoroutineScope()
        val listState = rememberScalingLazyListState()
        ScalingLazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    coroutineScope.launch { listState.scrollBy(it.verticalScrollPixels) }
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            state = listState,
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content
        )
        // On a round screen, a fixed-position square placed right at the literal top-left
        // corner gets clipped by the physical bezel — there are no pixels out there, so only a
        // sliver of it was ever visible. A square inset by less than ~0.293x the screen radius
        // from a corner isn't fully inside the inscribed circle (basic circle-vs-corner-point
        // geometry); 0.18x the smaller screen dimension clears that with margin, computed from
        // BoxWithConstraints rather than hardcoded so it holds on any round display size.
        val cornerInset = minOf(maxWidth, maxHeight) * 0.18f
        BackButton(
            onClick = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(start = cornerInset, top = cornerInset)
        )
    }
}

@Composable
private fun ScreenTitle(text: String) {
    Text(text = text, color = MenuGold, fontWeight = FontWeight.Black, fontSize = 20.sp)
}

@Composable
private fun RuleSection(title: String, body: String, example: (@Composable () -> Unit)? = null) {
    Box(
        modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, color = MenuGold, fontWeight = FontWeight.Black, fontSize = 15.sp)
            Text(text = body, color = Color.White, fontSize = 12.sp, textAlign = TextAlign.Center)
            example?.let {
                Spacer(Modifier.height(4.dp))
                it()
            }
        }
    }
}

@Composable
private fun ExampleHand(vararg cards: Card) {
    Row(horizontalArrangement = Arrangement.spacedBy(PixelCardSpacing)) {
        cards.forEach { PixelCard(card = it) }
    }
}

/** Simply-worded blackjack rules, illustrated with real [PixelCard] examples for the hands
 * that are easiest to show rather than describe (blackjack, a bust). Opened from the main
 * menu's TUTORIAL button; back arrow returns to the menu. */
@Composable
fun TutorialScreen(onBack: () -> Unit) {
    ScrollableInfoScreen(onBack = onBack) {
        item { Spacer(Modifier.height(40.dp)) }
        item { ScreenTitle("How to Play") }
        item {
            RuleSection(
                title = "Goal",
                body = "Get your hand closer to 21 than the dealer's, without going over."
            )
        }
        item {
            RuleSection(
                title = "Card values",
                body = "Number cards count as their number. J, Q and K count as 10. An Ace counts as 1 or 11 — whichever helps your hand more."
            )
        }
        item {
            RuleSection(
                title = "Blackjack",
                body = "An Ace plus a 10-value card as your first two cards is a Blackjack — an instant win that pays 3:2."
            ) {
                ExampleHand(Card(Rank.ACE, Suit.SPADES), Card(Rank.KING, Suit.HEARTS))
            }
        }
        item {
            RuleSection(
                title = "Hit or Stand",
                body = "On your turn, Hit to draw another card, or Stand to keep your hand as it is."
            )
        }
        item {
            RuleSection(
                title = "Bust",
                body = "If your hand goes over 21, you bust and lose right away, no matter what the dealer has."
            ) {
                ExampleHand(
                    Card(Rank.KING, Suit.CLUBS),
                    Card(Rank.QUEEN, Suit.DIAMONDS),
                    Card(Rank.FIVE, Suit.SPADES)
                )
            }
        }
        item {
            RuleSection(
                title = "Double down",
                body = "Double your bet and take exactly one more card, then your turn ends automatically."
            )
        }
        item {
            RuleSection(
                title = "Split",
                body = "If your first two cards match in rank, split them into two separate hands, each played out on its own."
            ) {
                ExampleHand(Card(Rank.EIGHT, Suit.SPADES), Card(Rank.EIGHT, Suit.HEARTS))
            }
        }
        item {
            RuleSection(
                title = "Dealer's turn",
                body = "Once you're done, the dealer reveals their hidden card and must keep hitting until reaching 17 or more."
            )
        }
        item {
            RuleSection(
                title = "Push",
                body = "If you and the dealer end up with the same total, it's a push — your bet is simply returned."
            )
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Box(modifier = Modifier.padding(horizontal = 26.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(text = title, color = MenuGold, fontWeight = FontWeight.Black, fontSize = 16.sp)
            content()
        }
    }
}

/** Settings screen with expandable sections. Only "About Game" exists so far — more sections
 * (sound, haptics, etc.) are expected to be added here later. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val versionName = remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "—"
    }
    ScrollableInfoScreen(onBack = onBack) {
        item { Spacer(Modifier.height(40.dp)) }
        item { ScreenTitle("Settings") }
        item {
            SettingsSection(title = "About Game") {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(text = "Blackjack", color = Color.White, fontSize = 13.sp)
                    Text(text = "Version $versionName", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                    Text(
                        text = "A pixel-art casino card game for Wear OS.",
                        color = Color.White,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}

/** Shown for Resume/New Game until Story Mode actually exists — see the project CLAUDE.md
 * checklist for the (large) list of unresolved Story Mode design questions. */
@Composable
fun StoryComingSoonScreen(onBack: () -> Unit) {
    ScrollableInfoScreen(onBack = onBack) {
        item { Spacer(Modifier.height(70.dp)) }
        item { ScreenTitle("Story Mode") }
        item {
            Text(
                text = "Coming soon — the casino career is still being written.",
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 30.dp, vertical = 8.dp)
            )
        }
        item { Spacer(Modifier.height(28.dp)) }
    }
}
