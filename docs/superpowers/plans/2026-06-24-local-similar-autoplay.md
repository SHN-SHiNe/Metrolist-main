# Local Similar Autoplay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a local-only recommendation playback mode that dynamically plays the most similar local song when the current queue has no next item.

**Architecture:** Move the local similarity scoring rules into a reusable pure Kotlin helper. `MusicService` uses the helper on `STATE_ENDED` before falling back to same-folder playback, and Compose exposes a local-only toggle button backed by DataStore.

**Tech Stack:** Kotlin, Media3 Player, Room, Jetpack DataStore, Jetpack Compose, JUnit 4.

---

### Task 1: Shared Local Similarity Selector

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/localmusic/LocalSimilarSongSelector.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/localmusic/LocalSimilarSongSelectorTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
@Test
fun selectsHighestSimilarityCandidateOutsideRecentHistory()

@Test
fun skipsCurrentSongAndFallsBackWhenAllCandidatesAreRecent()
```

- [ ] **Step 2: Verify red**

Run:

```powershell
.\.gradle-local\gradle-9.4.1\bin\gradle.bat --no-daemon --no-configuration-cache :app:testFossDebugUnitTest --tests "com.metrolist.music.localmusic.LocalSimilarSongSelectorTest"
```

Expected: compilation fails because `LocalSimilarSongSelector` does not exist.

- [ ] **Step 3: Implement minimal selector**

The selector accepts lightweight song analysis rows, requires full emotion vectors, requires BPM, applies compatible Camelot keys when available, ranks by emotion distance, and excludes recent IDs before falling back to non-current candidates.

- [ ] **Step 4: Verify green**

Run the same test command and expect `BUILD SUCCESSFUL`.

### Task 2: Playback Service Integration

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/playback/MusicService.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/constants/PreferenceKeys.kt`

- [ ] **Step 1: Add preference key**

Add `LocalSimilarAutoplayKey = booleanPreferencesKey("localSimilarAutoplay")`.

- [ ] **Step 2: Track recent local IDs**

Keep a small in-memory recent local playback list in `MusicService`, updated on media item transitions.

- [ ] **Step 3: Use selector on queue end**

On `STATE_ENDED`, after repeat modes and queue-next handling, if autoplay is enabled, the toggle is enabled, the current item is local, and there is no next media item, choose the next similar local song and play it.

- [ ] **Step 4: Preserve fallback**

If no valid recommendation exists, keep existing same-folder next-song behavior.

### Task 3: Player Controls

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/ui/player/Queue.kt`

- [ ] **Step 1: Add button in full player controls**

Show a `similar` icon button only when the current media item is local. Active state reflects `LocalSimilarAutoplayKey`.

- [ ] **Step 2: Add button in queue bottom controls**

Show the same local-only toggle beside shuffle/repeat controls so it belongs to playback-order controls.

### Task 4: Verification

- [ ] **Step 1: Run selector tests**

```powershell
.\.gradle-local\gradle-9.4.1\bin\gradle.bat --no-daemon --no-configuration-cache :app:testFossDebugUnitTest --tests "com.metrolist.music.localmusic.*"
```

- [ ] **Step 2: Build debug APK**

```powershell
.\.gradle-local\gradle-9.4.1\bin\gradle.bat --no-daemon --no-configuration-cache :app:assembleFossDebug
```

- [ ] **Step 3: Install**

```powershell
C:\Users\SHiNe\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 70ce70c6 install -r -d app\build\outputs\apk\foss\debug\app-foss-debug.apk
```
