# MorseNavigation — Phase 1

Phone-in-pocket haptic turn cues from Google Maps (two-wheeler mode) navigation. See `spec.md` for the original spec.

## Haptic vocabulary

| Turn | Pulses | Coming up (~25 s before) | Now (~9 s before, or "now") |
|---|---|---|---|
| Left (incl. keep / slight / sharp left) | 2 | 200 ms on, 700 ms off, soft | 200 ms on, 200 ms off, strong |
| Right (incl. keep / slight / sharp right) | 3 | same | same |
| U-turn | 4 | same | 150 ms on, 150 ms off, strong |

Google Maps' navigation notification carries the turn only as its arrow icon (text is just distance, road and ETA). `IconManeuverMatcher` loads Maps' own `maneuver_*` drawables by name and picks the nearest by alpha-mask difference (`IconMatch`); `ManeuverIcons` maps drawable names to cues. Roundabouts, merges, straight and arrival give no cue.

Cue timing is by seconds to the turn: `NavStore` estimates speed from how fast the distance drops and fires coming-up at ≤ 26 s (100–500 m) and now at ≤ 10 s (30–150 m). Until speed is known it uses 200 m / 80 m; when stopped only now fires, at 30 m. Each maneuver vibrates at most once per phase; a reroute doesn't repeat cues. A new instruction cancels any cue still playing.

## Use

1. Install the debug APK (`adb install -r app/build/outputs/apk/debug/app-debug.apk`).
2. Open the app and grant notification access, the ride notification, and unrestricted battery.
3. Try the test buttons to feel the patterns.
4. Tap **Start ride**, start Maps navigation, pocket the phone. Stop from the app or the notification.
5. After a ride, **Copy log** shows exactly what Maps sent; use it to tune `ManeuverParser`.

Nothing is persisted: the store and debug log live in memory only.

## Build

Requires JDK 17 and the Android SDK (platform 36). Open in Android Studio, or:

```
gradle wrapper        # once, to generate ./gradlew
./gradlew test assembleDebug
```

Tunables: `NavStore.ADVANCE_SECONDS` / `IMMINENT_SECONDS` and the min/max/fallback distances, `NavStore.REAPPEAR_MARGIN_METERS`, `IconMatch.MAX_DISTANCE` / `MIN_MARGIN`, patterns in `Haptics`.

Watch the decisions live with `adb logcat -s MorseNav`.

## Phase 2: boAt Wave Fury watch

During a ride, cues also go to the watch (switch with **Cues: phone + watch / phone only / watch only**; not persisted). `WatchCues` opens a second GATT client on the link the boAt app keeps to the watch — no pairing needed — and plays each cue as a burst of notifications: 2/3/4 ticks for left/right/U-turn, 1000 ms apart when coming up and 500 ms when now, each showing the turn (`LEFT 220 m`) and road.

Protocol (SMA, the boAt app's `szabh/smable3` SDK; found from a btsnoop capture and decompiling with REA):

- Nordic UART service `6e400001-b5a3-f393-e0a9-e50e24dcca9e`: write `…0002` (without response), notify `…0003`.
- Frame: `ab | 01 (request) / 11 (ACK) | length (2, BE) | CRC-16/ARC of payload (2, BE) | command key flag data…` — see `SmaFrame`.
- Working on the Wave Fury: `FIND_WATCH 0x0234` (rings + vibrates) and `NOTIFICATION 0x0401` (one ~300 ms tick + text; `SmaNotification`). Acknowledged but ignored: `NOTIFICATION2`, `NAVI_INFO 0x024d`, `GOOGLE_NAVI_INFO 0x0720` (`SmaNavi`). `VIBRATION 0x020b` is a setting (reads 1), not a trigger.
- The watch skips a notification whose timestamp (1 s resolution) equals the previous one, so each gets a unique second (`WatchLink.uniqueStamp`).
- The watch takes a variable 0.1–1.3 s per notification and vibrates when it handles it, so ticks after the first are paced from its ACKs.

With the screen locked Maps may update only every ~10 s; `NavStore.onClock` predicts the distance between updates from the speed (up to 15 s) so cues still fire on time.

Watch experiments (Find My Watch, read vibration, navigation and notification probes, tick burst) are in the app's **Watch (Phase 2 test)** section; they need Android 13+ and the Nearby devices permission.
