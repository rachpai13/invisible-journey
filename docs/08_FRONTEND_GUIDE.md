# Frontend guide

How to change what the app **looks like and how it behaves on screen** without touching sensors or meter logic. Written for a person or an AI assistant (Claude Code) who has just cloned the repo.

Everything visual lives in `app/src/main/java/com/invisiblejourney/ui/` (about 2,000 lines of Jetpack Compose + Material 3). The UI only *reads* data; it never computes meter values.

---

## 1. Run it in 5 minutes

1. Install Android Studio, **File → Open** the repo folder, let Gradle sync.
2. Copy `local.properties.example` to `local.properties` if Android Studio did not create one (it only needs `sdk.dir`; `TOMTOM_API_KEY` is optional).
3. Run on an emulator or phone. **You need no sensors to work on the UI:**
   - **Customer mode → "See a journey"** opens built-in sample journeys (a complete recorded ride). This covers the library, replay (dial, map, chips) and story screens.
   - **Rider mode → demo ride** runs a scripted ride through the real engine, so the live meter screen moves without GPS.
4. Command line build check: `gradlew assembleDebug` (set `JAVA_HOME` to Android Studio's `jbr` folder on Windows). Run `gradlew test` after any change that touches `domain/`.

There are no `@Preview` functions yet. If you want fast iteration on one component, add a preview to the file you are editing; `ui-tooling` is already a dependency. `SampleJourneys.generate(Scenario.HOT_AFTERNOON)` returns a full `JourneyRecord` you can build a `MeterState` from for previews.

---

## 2. Map of the UI

```
ui/
├── Theme.kt                  Palette, Dimens, Num (number styles), Format, PhaseText, InvisibleJourneyTheme
├── components/
│   ├── Kit.kt                Generic widgets: FitText, TopBar, Tag, BigButton, SwipeToConfirm, SelectTile, StatusLight, Card
│   ├── Meter.kt              Journey widgets: MeterDial, PhaseStepper, StatTile/StatGrid, ConditionStrip, TimeBreakdownBar, SectionTitle
│   └── JourneyMap.kt         osmdroid map (OpenStreetMap) wrapped for Compose
└── screens/
    ├── ModeSelectScreen.kt   First screen: Rider or Customer
    ├── RiderScreens.kt       RiderHomeScreen (start a ride, past rides) and LiveJourneyScreen (recording)
    └── CustomerScreens.kt    LibraryScreen, ReplayScreen, StoryScreen
```

Navigation is a single `NavHost` in `AppNav()` at the bottom of [`MainActivity.kt`](../app/src/main/java/com/invisiblejourney/MainActivity.kt):

| Route | Screen | Reached from |
|---|---|---|
| `mode` | `ModeSelectScreen` | app start |
| `rider` | `RiderHomeScreen` | mode → Rider |
| `live` | `LiveJourneyScreen` | rider home → start / running banner |
| `library` | `LibraryScreen` | mode → Customer |
| `replay/{id}` | `ReplayScreen` | library row |
| `story/{id}` | `StoryScreen` | replay, finished live ride, rider home row |

Screens take plain callbacks (`onBack`, `onOpen`, ...) and never hold a `NavController`. To add a screen: write a `@Composable XScreen(...)` in `ui/screens/`, add a `composable("route") { ... }` block in `AppNav()`, and call `nav.navigate("route")` from the screen that links to it.

---

## 3. Where the data comes from

Screens read two sources. Both are already exposed as state, so treat them as read-only inputs.

| Source | Used by | How a screen gets it |
|---|---|---|
| `ActiveJourney.state` (`StateFlow<ActiveJourneyState?>`, in `service/JourneyRecorder.kt`) | `LiveJourneyScreen`, rider home running banner | `collectAsState()`. Updated about once a second while recording. |
| `JourneyStore` (`context.container.store`) | Library, Replay, Story, rider home list | `store.items` (list, `collectAsState()`), `store.load(id)` (suspend, returns `JourneyRecord?`) |

The two key types, both in `domain/model/Models.kt`:

- **`MeterState`**: everything the meter shows at one instant (elapsed, moving/waiting time, distance, speed, stop count, potholes, roughness, congestion, temperature, rain, AQI, ...). Nullable fields mean "no data yet", so show `—`, not `0`.
- **`JourneySummary`** (inside `JourneyRecord.summary`): totals for the story screen.

`ReplayScreen` builds a `MeterState` for any moment with `meterAt(record, t)`, so any widget that takes a `MeterState` works for both live and replay. Keep new widgets that way: take a `MeterState`, not a service.

**If a screen needs a value that does not exist on `MeterState`/`JourneySummary`, that is a `domain/` change, not a UI change.** Add the field with a default value (so old saved `.journey.json` files still load), compute it in `JourneyEngine`/`SummaryBuilder`, add a test, then display it. See `docs/02_SENSORS.md` and `docs/06_DATA_MODEL.md`.

---

## 4. Design system (use these, don't hard-code)

**Colours: `Palette`** in `Theme.kt`. Auto-rickshaw theme: yellow `AutoYellow` (primary), green `AutoGreen`, pure black background `Ink`, `Surface`/`SurfaceHigh` for cards, `Muted`/`Faint` for secondary text, `Danger`/`Warn`/`Info` for status. Helpers map data to colour: `Palette.speed(kmh)`, `Palette.stop(type)`, `Palette.roughness(r)`. The app is dark-only, high contrast, because it is read in sunlight on a handlebar mount.

**Sizes: `Dimens`**: `Gutter` 16dp screen edge, `Gap` 10dp between items, `Touch` 56dp minimum tap target, `ButtonHeight` 60dp, `HeroButton` 72dp, `Radius` 16dp. Keep touch targets at least `Dimens.Touch`; riders wear gloves.

**Numbers: `Num.Hero / Big / Small`**: tabular digits so values don't jiggle while ticking. Use them for anything that updates live.

**Text formatting: `Format`**: `km`, `clock` (4:05), `duration` ("4 min"), `minutes`, `etaDelta`. Use these instead of ad hoc `String.format`.

**Labels for phases: `PhaseText`**: short names and the rider action text per `Phase`.

**Fixed-size tiles: wrap text in `FitText`** (`Kit.kt`). It shrinks text to fit instead of wrapping or pushing neighbours. Most layout bugs in tiles come from using plain `Text` with long values.

**Reusable widgets** (check here before writing a new one): `TopBar`, `Tag`, `BigButton`, `SwipeToConfirm` (hold-and-slide to avoid accidental taps), `SelectTile`, `StatusLight`, `Card`, `StatTile`/`StatGrid`, `MeterDial`, `PhaseStepper`, `ConditionStrip`, `TimeBreakdownBar`, `SectionTitle`, `JourneyMap`.

Strings are written inline in the Composables (only the app name is in `res/values/strings.xml`). Edit the text where it appears; `grep` for the visible phrase to find it.

---

## 5. Recipes

| I want to... | Edit |
|---|---|
| Change brand colours / dark theme | `Palette` and `darkColorScheme(...)` in `Theme.kt` |
| Change font sizes | `Num` and the `typography` block in `InvisibleJourneyTheme` |
| Rename or reword a button/label | Search the visible text in `ui/screens/*.kt` |
| Change which stat tiles are shown | `StatGrid` in `Meter.kt` (one `StatTile` per metric) |
| Restyle the dial | `MeterDial` in `Meter.kt` (drawn on a Canvas) |
| Change the weather/air/road chips | `ConditionStrip` in `Meter.kt` |
| Change the post-delivery story | `StoryScreen` and `EtaComparison` in `CustomerScreens.kt` |
| Change replay controls / speeds | `PlaybackBar` in `CustomerScreens.kt` |
| Change the map trail / markers / legend | `JourneyMap.kt`, `MapLegend` in `CustomerScreens.kt` |
| Change the first screen | `ModeSelectScreen.kt` |
| Change the app icon | `res/drawable/ic_launcher.xml` (vector) |
| Add a new screen | See section 2 |
| Add light theme | Add a `lightColorScheme` in `InvisibleJourneyTheme`; every colour is already centralised in `Palette`, but check `Color.White` / `Color.Black` literals with `grep` |

---

## 6. Rules the UI must keep

These come from the project brief; don't break them while restyling.

- **Honesty:** every metric has a `SourceTag` (`PHONE` / `ESP32` / `API` / `ESTIMATED`). If a value is estimated, say so (see `congestionNote` in `Meter.kt`). Missing data shows `—`, never a fake `0`.
- **Privacy:** no rider name, photo or identity on screen or in exports. The rider starts and stops recording.
- **Zero cost:** no paid map/SDK. Maps are osmdroid + OpenStreetMap tiles. Do not add Google Maps or any billed API.
- **Scope:** only factors 1–8 (location, speed, distance, waiting, potholes/roughness, congestion, weather, air quality).
- **Single-threaded engine:** UI code must never call `JourneyEngine` directly. Go through `JourneyTrackingService` / `ActiveJourney` / `JourneyStore`.
- **Don't add Hilt or Room.** DI is hand-written (`AppContainer`), storage is JSON files.

---

## 7. Checklist before you finish a UI change

1. `gradlew assembleDebug` compiles.
2. `gradlew test` passes (UI changes rarely break it, domain changes can).
3. Open the affected screen with a sample journey and, for live screens, a demo ride.
4. Check a small phone (about 360dp wide) and large font size: tiles use fixed heights, so look for clipped text.
5. Keep touch targets at least `Dimens.Touch`.

---

## 8. Prompting Claude Code on this repo

Open the repo in Claude Code; `CLAUDE.md` is loaded automatically and points here. Good prompts name the screen and the outcome:

- "Make the live meter dial bigger and move the stat tiles below it. Only edit `ui/`."
- "Add a new `Card` on the story screen showing time spent per phase, using `JourneySummary`."
- "Change the theme to a light palette that stays readable in sunlight."

If you ask for a new *number* (a new metric), expect a `domain/` change plus a unit test, as described in section 3.
