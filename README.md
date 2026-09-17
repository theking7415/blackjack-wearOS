# Blackjack (Wear OS)

Kotlin + Jetpack Compose for Wear OS. Targets Pixel Watch 5 (Wear OS 6, 480x480 round).

## Status

**Quick Play is functional**: standard casino rules (4-deck shoe, dealer hits on soft 17,
blackjack pays 3:2), bet/hit/stand loop, bankroll tracking. No persistence yet — bankroll
resets on app restart. Play-tested on a physical Pixel Watch 5.

**Story mode is not started.** Per the top-level CLAUDE.md, several design questions need
answers before implementation: table tiers/buy-ins, loan shark mechanics, save file format,
character/dialogue system. Tackle these as a separate pass.

## Project layout

- `app/src/main/java/com/sensinglocal/blackjack/game/` — pure game logic (`Card.kt`, `BlackjackEngine.kt`), no Android dependencies, easy to unit test.
- `app/src/main/java/com/sensinglocal/blackjack/BlackjackViewModel.kt` — exposes engine state to Compose.
- `app/src/main/java/com/sensinglocal/blackjack/MainActivity.kt` — Wear Compose UI.

## Building & running

To build or deploy, open the project folder in Android Studio and trigger a standard Gradle Sync, or run:

```
./gradlew installDebug
```

with the watch connected via wireless debugging (see top-level CLAUDE.md for the adb connect
command — the port changes if wireless debugging is toggled, so re-check it if the install
fails to find a device).

## Next steps

- Play-test on-device: verify layout on the round screen, check bust/dealer-turn readability.
- Add bankroll persistence (DataStore) so progress survives app restarts.
- Decide table tiers and buy-in curve, then build the tier-select screen ahead of story mode.
