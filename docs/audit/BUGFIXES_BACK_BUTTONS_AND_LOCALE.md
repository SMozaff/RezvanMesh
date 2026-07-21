# Bug Audit — Back Buttons & Locale Detection

Session scope: full audit of every screen's back-button wiring, plus the reported locale-detection bug.

---

## Locale bug — **Fixed**

**Root cause:** `LocaleHelper.detectSystemLanguage()` scanned the device's *entire* configured locale list (`Configuration.locales`, which can have multiple entries — e.g. a secondary keyboard language) and returned Farsi if it appeared **anywhere** in that list, not just as the primary/display language. A phone set to English but with Farsi present anywhere in its locale list (common if a secondary language/keyboard was ever added) would launch the app in Farsi.

**Fix:** Now checks only `configLocales[0]` — the actual primary system locale, i.e. what the user picked as their phone's display language. Falls back to English if that primary locale isn't Farsi.

**File:** `android/app/src/main/java/com/rezvani/mesh/utils/LocaleHelper.kt`

---

## Back-button / navigation audit — every screen checked

| Screen | Before | After |
|---|---|---|
| `DiagnosticsScreen` | Back button optional (`(() -> Unit)? = null`) — correctly wired today, but the nullable signature allowed it to silently vanish if any future call site forgot to pass a callback | Made `onNavigateBack` a required, non-nullable parameter — can no longer regress into a missing back button |
| `SettingsScreen` | Accepted an `onNavigateBack` parameter that was **never invoked anywhere** — dead code masquerading as wired navigation (Settings correctly has no back button, being a tab root, but the unused param was misleading) | Removed the dead parameter entirely; updated `NavGraph.kt` call site to match |
| `ChatsScreen` | `onNewChannelClick` and `onEmergencyClick` were accepted but **never called** in the UI — no button anywhere triggered them | Wired both into the top bar's actions row (icon buttons), so they now function |
| `VoiceScreen` | **Completely unreachable.** Registered as a route in `NavGraph.kt`, but nothing in the entire app navigated to it — no bottom-nav entry, no button, no link. Had no back button at all (no `Scaffold`/`TopAppBar`), which would have been a second bug if it were ever reached by pushing on top of another screen. Per the README's own architecture diagram, PTT/Voice was meant to be a primary feature on par with Emergency. | Added a new mic icon button in `ChatsScreen`'s top bar (`onVoiceClick`) that navigates to `"voice"`. Converted `VoiceScreen` to a proper `Scaffold` with a `TopAppBar` and a real back button wired to `onNavigateBack` / `popBackStack()`. |

### Confirmed correct, no changes needed
`AdvancedNetworkScreen`, `ChannelDetailScreen`, `ChatDetailScreen`, `ContactsScreen`, `CreateChannelScreen`, `QrScannerScreen` all had real, correctly wired back buttons already. `NetworkScreen`, `ChannelsScreen`, `EmergencyScreen`, and (now) `SettingsScreen` correctly have **no** back button, since they're bottom-nav tab roots (system back button / tab switch handles navigation, per standard Android convention — a persistent in-app back arrow on a root tab would be non-standard UX and isn't used consistently anywhere else in the app either).

### Left alone (separate, pre-existing decision)
`MessagesScreen.kt` — confirmed still completely unreferenced anywhere in the app (an earlier prototype superseded by `ChatsScreen`/`ChatDetailScreen`). Not part of this bug report; left untouched per the existing in-code note in `NavGraph.kt` explaining why its route was already removed.

---

## Files changed this session

- `android/app/src/main/java/com/rezvani/mesh/utils/LocaleHelper.kt`
- `android/app/src/main/java/com/rezvani/mesh/ui/screens/DiagnosticsScreen.kt`
- `android/app/src/main/java/com/rezvani/mesh/ui/screens/SettingsScreen.kt`
- `android/app/src/main/java/com/rezvani/mesh/ui/screens/ChatsScreen.kt`
- `android/app/src/main/java/com/rezvani/mesh/ui/screens/VoiceScreen.kt`
- `android/app/src/main/java/com/rezvani/mesh/ui/navigation/NavGraph.kt`

No new string resources were needed — `new_channel`, `emergency_title`, `back`, and `voice_broadcast_title` all already existed in both `values/strings.xml` and `values-fa/strings.xml`.

## Verification limitation

**No Kotlin/Gradle toolchain is available in this sandbox** — every change was reviewed by hand (signatures, imports, brace matching, call-site consistency across files) but nothing was compiled. Run a full Gradle build (`./gradlew assembleDebug`) and manually test:
1. Set phone to English with Farsi as a secondary/keyboard language (if your device supports listing multiple locales) → app should now open in English.
2. Navigate to Voice via the new mic icon on the Chats screen → confirm back button returns to Chats.
3. Navigate to Diagnostics (via Settings) → confirm back button still works.
4. Confirm Settings screen still has no back arrow (expected/correct) and its bottom-nav tab switching still works normally.
