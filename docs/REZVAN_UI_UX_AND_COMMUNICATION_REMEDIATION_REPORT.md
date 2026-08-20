# RezvanMesh UI/UX and Communication Remediation Report

**Baseline commit:** `887be78fb23f1149ea2d4eb39a30aec0e40073de`  
**Remediation branch:** `fix/communication-integrity-uiux`  
**Final commit:** Pending verification and commit  
**Report status:** Implementation and local verification record  
**Prepared by:** Manus AI

> **Safety position:** The remediation treats local queue acceptance as a local fact only. It does **not** represent a remote radio write, protocol acknowledgement, or recipient delivery. Voice/PTT remains deliberately unavailable pending a complete authenticated protocol and physical-device validation.

## Executive Summary

The remediation removes the most dangerous false-success paths for emergency and text messaging. A shared `SendResult` contract now distinguishes mesh unavailability, lack of reachable peers, local queue acceptance, and local failures. Emergency and message UIs no longer use time delays to declare success. The send lifecycle now keeps an outgoing message in a queued state after local submission and marks it failed if the service or transport cannot accept it. [1] [2] [3]

Production BLE transmission now uses the existing fragmentation layer for oversized logical packets and the GATT server reassembles fragments before mesh parsing. The reassembler was additionally bounded by maximum fragment count and aggregate payload size. Focused JVM unit tests cover default-MTU large-payload round trips, ordering, duplicate fragments, passthrough, and memory bounds. [4] [5] [6]

Voice/PTT has **not** been promoted. The existing raw recording path could not truthfully claim complete secure, end-to-end voice delivery. The voice interface now presents an unavailable state, and the service rejects voice submission rather than sending an oversized raw recording. The legacy Emergency Reception control is thereby removed from the reachable UI, avoiding a non-operational safety setting. [7] [8]

| Area | PASS | PARTIAL | BLOCKED / NOT VERIFIED |
|---|---:|---:|---:|
| Communication-integrity items | 4 | 2 | 1 |
| UI/UX roadmap items | 5 | 6 | 4 |
| Targeted automated tests | 1 | 0 | 0 |
| Physical-device communication tests | 0 | 0 | 1 |

The Android debug sources compile and the debug unit-test task passes locally using Android SDK Platform 35 and JDK 17. No physical Android device or emulator was available, and the Rust toolchain was not installed; therefore, no claim of physical-device or native Rust test verification is made.

## Verification-Level Legend

| Label | Meaning |
|---|---|
| **STATICALLY VERIFIED** | Source paths, callers, and error propagation were inspected after implementation. |
| **UNIT TESTED** | JVM test task completed successfully for the named behavior. |
| **EMULATOR TESTED** | Not performed. |
| **PHYSICAL DEVICE TESTED** | Not performed. |
| **NOT VERIFIED** | Requires an unavailable device, protocol capability, or toolchain. |

## Communication Integrity

### C01 — Transport-result contract

| Field | Status |
|---|---|
| Before | User-visible send APIs returned `Unit`, nullable service calls could silently do nothing, and caller state could still become successful. |
| Root cause | The service and action dispatcher did not return a local transport result. |
| Implementation | Added `SendResult` with `NotReady`, `NoReachablePeer`, `Queued(peerCount)`, and `Failed(reason)`. `RadioController`, `ActionDispatcher`, and `RezvanRadioService` now propagate this result. [1] [2] [3] |
| After | Callers can expose local queue acceptance or a specific non-success reason. `Queued` is documented as local-only and never delivery. |
| Evidence | `SendResult.kt`; send contract call-chain updates. [1] [2] [3] |
| Tests | **STATICALLY VERIFIED**; Android debug sources compile. |
| Commit | Pending final commit. |
| Remaining risk | The protocol does not currently emit a durable remote acknowledgement; `Acknowledged` and `Delivered` are intentionally not fabricated. |

### C02 — Emergency transmission semantics

| Field | Status |
|---|---|
| Before | The emergency view model invoked a nullable service, delayed for one second, then displayed “Alert sent.” |
| Root cause | Optimistic UI state was independent of engine and radio result. |
| Implementation | Emergency submission now awaits `SendResult`. It shows a clearly qualified queued message only after local queue acceptance; mesh-unavailable, no-peer, and failure conditions render a failure state. Duplicate submission is disabled while submitting. [2] [9] |
| After | The UI never calls queued local work “delivered” or “sent.” |
| Evidence | `EmergencyViewModel.kt` and `EmergencyScreen.kt`. [9] |
| Tests | **STATICALLY VERIFIED**; Android debug sources compile. |
| Commit | Pending final commit. |
| Remaining risk | A local queue can still fail after acceptance; no remote acknowledgement exists. Physical device testing is mandatory before release. |

### C03 — BLE fragmentation and reassembly

| Field | Status |
|---|---|
| Before | The sender issued one GATT write for each logical packet; reassembly existed only in diagnostics. |
| Root cause | `BleFragmenter` and `BleReassembler` were not connected to production send/receive paths. |
| Implementation | `BlePacketSender` fragments logical packets larger than `(negotiated MTU − ATT overhead)` and queues each fragment. `RadioControllerImpl` tracks negotiated MTU per GATT peer, uses it when constructing senders, and feeds server writes into a bounded `BleReassembler` before calling the mesh parser. [4] [5] [6] |
| After | Oversized logical packets are fragmented for production GATT transmission. Inbound fragment sequences are reassembled before mesh parsing. |
| Evidence | `BlePacketSender.kt`, `BleFragmenter.kt`, and `RadioControllerImpl.kt`. [4] [5] [6] |
| Tests | **UNIT TESTED** for large/default-MTU round trip, out-of-order sequence, duplicate handling, passthrough, max payload, and max fragment count. |
| Commit | Pending final commit. |
| Remaining risk | Android GATT behavior, MTU negotiation, disconnect handling, and real peer interoperability require physical-device tests. |

### C04 — Voice transport

| Field | Status |
|---|---|
| Before | A raw recorded payload was passed directly to broadcast transport, the UI presented an optimistic sent state, and no complete authenticated receive pipeline was present. |
| Root cause | There was no production voice envelope, receive policy, integrity mechanism, or end-to-end device validation. |
| Implementation | Voice send calls return a deterministic `Failed` result. The Voice route renders an unavailable state and contains no record, transmit, PTT, or Emergency Reception control. [7] [8] |
| After | The product no longer implies that emergency voice broadcasting is available. |
| Evidence | `RezvanRadioService.kt` and `VoiceScreen.kt`. [7] [8] |
| Tests | **STATICALLY VERIFIED**; Android debug sources compile. |
| Commit | Pending final commit. |
| Remaining risk | Voice is intentionally disabled. A future implementation must add authenticated envelope, fragmentation, receive validation, storage/playback policy, replay protections where required, and device tests before exposure. |

### C05 — Direct and channel message lifecycle

| Field | Status |
|---|---|
| Before | Outgoing messages were persisted before a nullable send; stored state remained `SENDING`, while the conversation list forced `SENT`. |
| Root cause | No authoritative transition from persisted message to transport submission result. |
| Implementation | Outgoing state is explicitly named `QUEUED`. Direct and channel view models persist local text, await `SendResult`, and mark the row `FAILED` when local submission fails. The chat list maps the persisted latest-message state rather than forcing `SENT`. The detail UIs provide a progress indicator for queued and an accessible failure marker. [10] [11] [12] [13] |
| After | UI and list use a consistent, conservative local lifecycle. |
| Evidence | `MessageEntity.kt`, `MessageRepository.kt`, `ChatDetailViewModel.kt`, `ChannelDetailViewModel.kt`, `ChatsViewModel.kt`, and chat screens. [10] [11] [12] [13] |
| Tests | **STATICALLY VERIFIED**; Android debug sources compile. |
| Commit | Pending final commit. |
| Remaining risk | There is no protocol-linked message ID or remote delivery acknowledgement. `SENT`, `DELIVERED`, and `READ` remain reserved for future verified signals. |

### C06 — Emergency Reception and service readiness

| Field | Status |
|---|---|
| Before | Emergency Reception persisted a preference but did not affect inbound processing. The UI service bridge could also be disconnected from the Activity’s real service binding. |
| Root cause | The voice receive path was incomplete and the shared service bridge had no reliable registration from the navigation shell. |
| Implementation | Emergency Reception is hidden with the disabled voice UI. `MeshServiceConnection` now exposes reactive service and node-ID state, retains the registered UI bridge through service reconnects, and `MainScreenWithBottomNav` registers that bridge. [8] [14] [15] |
| After | No visible setting implies an unavailable receive capability. QR readiness and service consumers observe canonical state reactively. |
| Evidence | `VoiceScreen.kt`, `MeshServiceConnection.kt`, `MainScreenWithBottomNav.kt`, `NetworkScreen.kt`, and `ContactsScreen.kt`. [8] [14] [15] |
| Tests | **STATICALLY VERIFIED**; Android debug sources compile. |
| Commit | Pending final commit. |
| Remaining risk | Direct inbound-message behavior must be validated across Activity/service lifecycle changes on physical devices. |

## UI/UX Remediation

| Roadmap item | Status | Implementation or disposition | Verification |
|---|---|---|---|
| U01 — Onboarding | **PASS** | Explicit `onboarding_complete` preference replaces identity-derived completion; `MainActivity` renders `OnboardingScreen` before the main shell. [16] [17] | Static + compile |
| U02 — Battery exemption | **PASS** | Battery optimisation is no longer part of `allGranted`; it remains recommended in the setup screen. [16] | Static + compile |
| U03 — Hold-to-talk | **BLOCKED BY SAFE DISABLEMENT** | Voice is unavailable until the end-to-end transport is complete; no misleading PTT gesture remains. [8] | Static + compile |
| U04 — PTT discoverability | **PASS (withheld)** | Voice is not promoted in navigation; the retained route is a safe unavailable state. [8] [18] | Static + compile |
| U05 — Localization and RTL | **PARTIAL** | Language selection recreates the Activity to immediately apply the saved locale and direction. Hard-coded English remains in several legacy screens and requires language QA. [19] | Static + compile |
| Reactive theme | **PASS** | Theme preference now uses a shared-preference listener and applies without activity recreation. [16] | Static + compile |
| Network command center | **PARTIAL** | Reactive node ID fixes QR readiness. The broader redesign, trust model, and decision-oriented information architecture remain future work. [15] | Static + compile |
| Peer identity/trust | **BLOCKED** | No existing protocol-backed trust model was introduced. | Not verified |
| Semantic colors | **PARTIAL** | Existing Material semantic colors are used in changed flows; feature-wide token migration remains future work. | Static |
| Diagnostics separation | **PARTIAL** | Developer diagnostics are once again hidden behind the documented five-tap version action. [19] | Static + compile |
| Accessibility | **PARTIAL** | New transport-status icons include content descriptions and queued state uses a non-success progress indicator. Full TalkBack, font-scale, RTL, focus, and motion review remains required. [12] | Static |
| Channel failure UX | **PASS** | Private-channel join error now displays an actionable failure dialog. [20] | Static + compile |
| Motion/haptics | **BLOCKED** | Not added; operational correctness takes priority. | Not verified |

## Transport-State Definitions

| Internal State | User-Facing State | Exact Meaning |
|---|---|---|
| `NotReady` | Mesh unavailable — not sent | No usable service, engine, or radio controller was available at local submission time. |
| `NoReachablePeer` | No reachable mesh peer | Local packet construction completed but no discovered/live BLE peer could accept a broadcast or target. |
| `Queued(peerCount)` | Queued locally / queued for nearby mesh peer(s) | The local radio queue accepted the logical packet for the stated peer count. It does **not** prove a GATT write, remote receipt, or delivery. |
| `Failed(reason)` | Transmission failed | Packet construction or local queue submission failed before a truthful queued state was reached. |
| Persisted `QUEUED` | Queued | An outgoing text record exists locally and local transport accepted it. |
| Persisted `FAILED` | Failed | An outgoing text record exists locally but the local mesh could not accept it. |

## Permission Matrix

| Condition | App Accessible | Mesh Available | Warning / actual behavior |
|---|---|---|---|
| All essential permissions granted; battery unrestricted | Yes | Yes, subject to service/peer readiness | None from setup gate. |
| Battery restricted | Yes | Yes, with reduced background reliability risk | Setup calls battery exemption **recommended**, not required. A persistent in-app background warning is still future work. |
| Bluetooth off | No under current essential setup gate | No | User is directed to enable Bluetooth. Making non-mesh screens broadly accessible while Bluetooth is off remains future work. |
| Nearby Devices or location permission denied | No under current essential setup gate | No | User is directed to grant the essential radio permissions. |
| Mesh service/engine unavailable after entry | Yes | No | User-visible emergency/message action reports `Mesh unavailable — not sent`. |

## Physical-Device Test Matrix

> No physical Android devices were attached to this environment. The table is intentionally incomplete and must be executed before a production transport claim.

| Test | Device A | Device B | Result | Evidence |
|---|---|---|---|---|
| Direct message, discovered peer | Not run | Not run | **NOT VERIFIED** | Requires two physical devices. |
| Direct message, no peer | Not run | Not run | **NOT VERIFIED** | Local result behavior statically verified. |
| Emergency, no peer | Not run | Not run | **NOT VERIFIED** | Local result behavior statically verified. |
| Emergency, peer reachable | Not run | Not run | **NOT VERIFIED** | No remote acknowledgement claim. |
| BLE reconnect | Not run | Not run | **NOT VERIFIED** | Requires service lifecycle testing. |
| Default/low MTU fragmentation | Not run | Not run | **NOT VERIFIED** | JVM fragmentation tests pass; real GATT unverified. |
| Large payload reassembly | Not run | Not run | **NOT VERIFIED** | JVM test covers 31 KB logical payload. |
| Voice maximum duration | Not applicable | Not applicable | **WITHHELD** | Voice is deliberately disabled. |
| Receiver disabled | Not applicable | Not applicable | **WITHHELD** | Voice/Emergency Reception is deliberately hidden. |
| Battery-restricted background operation | Not run | Not run | **NOT VERIFIED** | OEM-dependent behavior. |

## Build and Test Results

| Command | Outcome |
|---|---|
| `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ANDROID_SDK_ROOT=/home/ubuntu/android-sdk bash ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1024m -XX:MaxMetaspaceSize=256m' :android:app:compileDebugKotlin --stacktrace` | **PASS** — Android debug Kotlin compilation succeeded. |
| `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/home/ubuntu/android-sdk ANDROID_SDK_ROOT=/home/ubuntu/android-sdk bash ./gradlew --no-daemon --max-workers=1 -Dorg.gradle.jvmargs='-Xmx1024m -XX:MaxMetaspaceSize=256m' :android:app:testDebugUnitTest --stacktrace` | **PASS** — Debug unit tests succeeded, including `BleFragmenterTest`. |
| `cargo test --workspace` | **NOT RUN** — Rust toolchain was not available in the sandbox. |

## Remaining Risks

The remediation intentionally does not conceal unresolved limitations. OEM-specific background limits still affect service reliability even after battery optimisation is made optional. Android-version and manufacturer coverage remains untested. The language catalog and RTL layouts require human QA because legacy hard-coded English remains. There is no remote protocol acknowledgement for messages or emergency broadcasts, so the current truthful maximum is local queue acceptance. Voice transmission/reception is not release-ready and remains disabled. Native Rust workspace tests and all physical-device testing remain outstanding.

## Final Recommendation

| Category | Recommendation |
|---|---|
| **Must fix before production** | Execute and document two-device GATT tests for direct messages, emergency broadcasts, MTU variation, reconnection, and background conditions. Add protocol-backed acknowledgement only if the UX needs delivery claims. Keep voice hidden until its authenticated end-to-end protocol and receive handling exist. |
| **Required before beta** | Complete localization/RTL review, verify permission flows across OEMs, provide an in-app background-reliability indicator, and test service reconnect/inbound persistence. |
| **Recommended before public release** | Build the network command-center redesign, peer identity/trust model, semantic color token migration, diagnostics separation, and formal accessibility review. |
| **Future improvements** | Add authenticated voice envelopes, replay protections where architecturally required, persistent media handling, playback policy, haptics/motion, and protocol-backed delivered/read states. |

## References

[1]: ../android/app/src/main/java/com/rezvani/mesh/radio/SendResult.kt "Shared local transport result contract"
[2]: ../android/app/src/main/java/com/rezvani/mesh/radio/RezvanRadioService.kt "Structured emergency, direct, channel, and disabled voice submission"
[3]: ../android/app/src/main/java/com/rezvani/mesh/radio/ActionDispatcher.kt "Action-envelope transport result propagation"
[4]: ../android/app/src/main/java/com/rezvani/mesh/radio/BlePacketSender.kt "Production sender fragmentation"
[5]: ../android/app/src/main/java/com/rezvani/mesh/radio/BleFragmenter.kt "Fragmentation and bounded reassembly"
[6]: ../android/app/src/main/java/com/rezvani/mesh/radio/RadioControllerImpl.kt "Negotiated MTU and GATT receive reassembly"
[7]: ../android/app/src/main/java/com/rezvani/mesh/radio/RezvanRadioService.kt "Voice safety disablement"
[8]: ../android/app/src/main/java/com/rezvani/mesh/ui/screens/VoiceScreen.kt "Withheld Voice/PTT UI"
[9]: ../android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/EmergencyViewModel.kt "Truthful emergency state mapping"
[10]: ../android/app/src/main/java/com/rezvani/mesh/data/entities/MessageEntity.kt "Persistent queued/failed lifecycle"
[11]: ../android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/ChatDetailViewModel.kt "Direct-message lifecycle"
[12]: ../android/app/src/main/java/com/rezvani/mesh/ui/screens/ChatDetailScreen.kt "Accessible message-status rendering"
[13]: ../android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/ChatsViewModel.kt "Chat-list status mapping"
[14]: ../android/app/src/main/java/com/rezvani/mesh/MeshServiceConnection.kt "Reactive service/node identity and UI bridge"
[15]: ../android/app/src/main/java/com/rezvani/mesh/ui/screens/NetworkScreen.kt "Reactive QR readiness"
[16]: ../android/app/src/main/java/com/rezvani/mesh/MainActivity.kt "Onboarding, permissions, battery, and theme application"
[17]: ../android/app/src/main/java/com/rezvani/mesh/ui/viewmodel/MainViewModel.kt "Explicit onboarding completion"
[18]: ../android/app/src/main/java/com/rezvani/mesh/ui/navigation/NavGraph.kt "Voice route containment"
[19]: ../android/app/src/main/java/com/rezvani/mesh/ui/screens/SettingsScreen.kt "Language, developer tools, and settings behavior"
[20]: ../android/app/src/main/java/com/rezvani/mesh/ui/screens/ChannelsScreen.kt "Private-channel join feedback"
