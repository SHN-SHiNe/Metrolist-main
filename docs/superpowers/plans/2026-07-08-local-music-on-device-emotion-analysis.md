# Local Music On-Device Emotion Analysis Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the local-music analysis loop that classifies songs as analyzed/pending, analyzes pending local files on-device with vibenet ONNX, and persists the results to the database and MP3 tags.

**Architecture:** Add a focused `localmusic.analysis` package with pure Kotlin DSP, Android audio decoding, ONNX inference, BPM/key analysis, orchestration, and tag writing. Keep scanning fast by reading tags only; user-triggered analysis runs in the background and updates existing `local_music` fields.

**Tech Stack:** Kotlin, Hilt, Room, MediaExtractor/MediaCodec, ONNX Runtime Android `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`, Compose, JUnit 4.

## Global Constraints

- Do not run ONNX inference during local scanning.
- Analysis applies only to real local files, not network streams or Media3 offline cache entries.
- A song is analyzed only when BPM, key, and all seven vibenet emotion fields are present.
- Persist DB results first; MP3 tag writing is best-effort and must not discard analysis if file writing fails.
- MP3 tag format must match the existing musicanalyzer frame names: `TBPM`, `TKEY`, seven `TXXX:*` emotion frames, `TMOO`, `TXXX:WM/Mood`, `TXXX:MOOD`, and `COMM::xxx`.
- UI must expose `立刻分析` from local song long-press and from the currently playing local song.
- After implementation, build, install to the connected phone, and open the app.

---

## File Structure

- Modify `gradle/libs.versions.toml`: add ONNX Runtime Android version and library alias.
- Modify `app/build.gradle.kts`: add `implementation(libs.onnxruntime.android)`.
- Add `app/src/main/assets/vibenet/efficientnet_model.onnx`: vibenet ONNX model copied from `jaeheonshim/vibenet`.
- Add `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/LocalMusicAnalysisModels.kt`: analysis result, status, tag-write result, helper predicates.
- Add `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/AudioMath.kt`: pure Kotlin math utilities, Hann windows, FFT, mel filter banks, power-to-dB.
- Add `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/AudioPreprocessor.kt`: Android URI decoding plus mel extraction.
- Add `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/VibenetOnDeviceAnalyzer.kt`: ONNX model session and emotion inference.
- Add `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/TempoKeyAnalyzer.kt`: BPM and key analysis from PCM.
- Add `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/LocalMusicTagWriter.kt`: MP3 ID3 rewrite.
- Add `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/LocalMusicAnalysisManager.kt`: analysis queue/orchestration.
- Modify `app/src/main/kotlin/com/metrolist/music/db/DatabaseDao.kt`: add one-song analysis update query.
- Modify `app/src/main/kotlin/com/metrolist/music/db/MusicDatabase.kt`: expose the DAO update through `MusicDatabase`.
- Modify `app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicViewModel.kt`: inject manager, expose analysis state, group filtered songs.
- Modify `app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicScreen.kt`: analyzed/pending sections, long-press menu, analyzing state.
- Modify player UI/menu files after locating the current local metadata panel: add `立刻分析` for the currently playing local song.
- Add tests under `app/src/test/kotlin/com/metrolist/music/localmusic/analysis/`.

---

### Task 1: Dependency And Model Asset

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/assets/vibenet/efficientnet_model.onnx`

**Interfaces:**
- Produces: ONNX Runtime classes available to app code.
- Produces: asset path constant `vibenet/efficientnet_model.onnx` for `VibenetOnDeviceAnalyzer`.

- [ ] **Step 1: Add version and library aliases**

Add:

```toml
onnxruntime = "1.27.0"
onnxruntime-android = { module = "com.microsoft.onnxruntime:onnxruntime-android", version.ref = "onnxruntime" }
```

- [ ] **Step 2: Add app dependency**

Add near other implementation dependencies:

```kotlin
implementation(libs.onnxruntime.android)
```

- [ ] **Step 3: Copy model asset**

Run:

```powershell
New-Item -ItemType Directory -Force app\src\main\assets\vibenet
Copy-Item "$env:TEMP\vibenet-inspect\vibenet\artifacts\efficientnet_model.onnx" app\src\main\assets\vibenet\efficientnet_model.onnx -Force
```

Expected: `app/src/main/assets/vibenet/efficientnet_model.onnx` exists and is about 18.5 MB.

- [ ] **Step 4: Build dependency check**

Run:

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
```

Expected: Gradle resolves `onnxruntime-android` successfully or shows only unrelated current-worktree compile errors.

---

### Task 2: Analysis Models And Classification

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/LocalMusicAnalysisModels.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/localmusic/analysis/LocalMusicAnalysisModelsTest.kt`

**Interfaces:**
- Produces: `LocalMusicAnalysisResult`
- Produces: `LocalMusicAnalysisStatus`
- Produces: `LocalMusicEntity.hasCompleteAnalysis(): Boolean`
- Produces: `LocalMusicAnalysisResult.moodSummary(): String`

- [ ] **Step 1: Write model tests**

Create tests for:

```kotlin
@Test fun completeAnalysisRequiresBpmKeyAndSevenEmotions()
@Test fun moodSummaryUsesManagedUppercaseNames()
@Test fun normalizedEmotionValuesClampToZeroOne()
```

- [ ] **Step 2: Implement models**

Define:

```kotlin
data class LocalMusicAnalysisResult(
    val bpm: Float,
    val keyName: String,
    val valence: Float,
    val energy: Float,
    val danceability: Float,
    val acousticness: Float,
    val instrumentalness: Float,
    val liveness: Float,
    val speechiness: Float,
)

enum class LocalMusicAnalysisStatus { Idle, Queued, Running, Complete, Failed }
```

`moodSummary()` must return:

```text
VALENCE:x | ENERGY:y | DANCEABILITY:z | ACOUSTICNESS:a | INSTRUMENTALNESS:b | LIVENESS:c | SPEECHINESS:d
```

- [ ] **Step 3: Run model tests**

Run:

```powershell
.\gradlew.bat :app:testFossDebugUnitTest --tests "*LocalMusicAnalysisModelsTest"
```

Expected: tests pass.

---

### Task 3: Pure DSP Utilities

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/AudioMath.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/localmusic/analysis/AudioMathTest.kt`

**Interfaces:**
- Produces: `AudioMath.hannWindow(size: Int): FloatArray`
- Produces: `AudioMath.resampleLinear(input: FloatArray, sourceRate: Int, targetRate: Int): FloatArray`
- Produces: `AudioMath.fftPower(frame: FloatArray, fftSize: Int): FloatArray`
- Produces: `AudioMath.melFilterBank(sampleRate: Int, fftSize: Int, melCount: Int, fMin: Float, fMax: Float): Array<FloatArray>`
- Produces: `AudioMath.powerToDb(power: FloatArray, topDb: Float = 80f): FloatArray`

- [ ] **Step 1: Write tests**

Test stable invariants:

```kotlin
assertEquals(640, AudioMath.hannWindow(640).size)
assertEquals(16000, AudioMath.resampleLinear(FloatArray(44100) { 1f }, 44100, 16000).size, 1)
assertEquals(513, AudioMath.fftPower(FloatArray(640), 1024).size)
assertEquals(128, AudioMath.melFilterBank(16000, 1024, 128, 0f, 8000f).size)
```

- [ ] **Step 2: Implement math**

Use iterative radix-2 FFT for `fftPower`, no external math dependency. Pad or truncate frames to `fftSize`.

- [ ] **Step 3: Run tests**

Run:

```powershell
.\gradlew.bat :app:testFossDebugUnitTest --tests "*AudioMathTest"
```

Expected: tests pass.

---

### Task 4: Audio Preprocessing And Vibenet ONNX Inference

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/AudioPreprocessor.kt`
- Create: `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/VibenetOnDeviceAnalyzer.kt`

**Interfaces:**
- Produces: `data class DecodedAudio(val samples: FloatArray, val sampleRate: Int)`
- Produces: `class AudioPreprocessor { suspend fun decodeMono16k(uri: Uri): DecodedAudio; fun extractVibenetMel(samples16k: FloatArray): Array<FloatArray> }`
- Produces: `class VibenetOnDeviceAnalyzer { suspend fun analyze(mel: Array<FloatArray>): VibenetEmotionResult }`

- [ ] **Step 1: Implement decoder**

Use `MediaExtractor` and `MediaCodec` to decode audio to PCM. Mix channels to mono and resample to 16 kHz with `AudioMath.resampleLinear`.

- [ ] **Step 2: Implement mel extraction**

Match vibenet:

```kotlin
val nFft = 1024
val hopLength = 320
val winLength = 640
val nMels = 128
val center = false
```

Return a tensor-shaped matrix compatible with ONNX input `[1, 128, time]`.

- [ ] **Step 3: Implement ONNX wrapper**

Load asset bytes, create `OrtEnvironment`, create one `OrtSession`, feed input name `x`, read output `out` with seven floats.

- [ ] **Step 4: Smoke compile**

Run:

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
```

Expected: code compiles.

---

### Task 5: BPM And Key Analyzer

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/TempoKeyAnalyzer.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/localmusic/analysis/TempoKeyAnalyzerTest.kt`

**Interfaces:**
- Produces: `class TempoKeyAnalyzer { fun estimateBpm(samples: FloatArray, sampleRate: Int): Float; fun estimateKey(samples: FloatArray, sampleRate: Int): String }`

- [ ] **Step 1: Write tests for synthetic signals**

Use synthetic pulse train at 120 BPM and a sine-rich A minor-like sample. Expected:

```kotlin
assertEquals(120f, analyzer.estimateBpm(pulses120, 16000), 4f)
assertEquals("Am", analyzer.estimateKey(aMinorSignal, 16000))
```

- [ ] **Step 2: Implement BPM**

Use frame energy/onset envelope, autocorrelation over 40-220 BPM, return strongest tempo.

- [ ] **Step 3: Implement key**

Use pitch-class energy from FFT peaks and Krumhansl-Schmuckler major/minor templates. Return vibenet/musicanalyzer-compatible key strings where major has no suffix and minor uses `m`, for example `A`, `Am`, `C#`, `C#m`.

- [ ] **Step 4: Run tests**

Run:

```powershell
.\gradlew.bat :app:testFossDebugUnitTest --tests "*TempoKeyAnalyzerTest"
```

Expected: tests pass.

---

### Task 6: Database Update And MP3 Tag Writer

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/db/DatabaseDao.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/db/MusicDatabase.kt`
- Create: `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/LocalMusicTagWriter.kt`
- Test: `app/src/test/kotlin/com/metrolist/music/localmusic/analysis/LocalMusicTagWriterTest.kt`

**Interfaces:**
- Produces DAO: `fun updateLocalMusicAnalysis(songId: String, bpm: Float, keyName: String, valence: Float, energy: Float, danceability: Float, acousticness: Float, instrumentalness: Float, liveness: Float, speechiness: Float, moodSummary: String?)`
- Produces DB facade: `fun updateLocalMusicAnalysis(songId: String, result: LocalMusicAnalysisResult)`
- Produces writer: `suspend fun writeMp3Tags(uri: Uri, result: LocalMusicAnalysisResult): LocalMusicTagWriteResult`

- [ ] **Step 1: Add DAO update**

Add a single `UPDATE local_music SET ... WHERE songId = :songId`.

- [ ] **Step 2: Add DB facade method**

Delegate to DAO and pass `result.moodSummary()`.

- [ ] **Step 3: Implement ID3 writer**

Read source stream, detect and skip existing ID3v2 tag if present, write new ID3v2 tag with managed frames, then stream-copy audio bytes to `contentResolver.openOutputStream(uri, "rwt")`.

- [ ] **Step 4: Write tag writer unit tests**

Use temp files and a fake stream helper around file URIs where possible. Verify output contains:

```text
ID3
TBPM
TKEY
TXXX
VALENCE
TMOO
COMM
```

- [ ] **Step 5: Run tests**

Run:

```powershell
.\gradlew.bat :app:testFossDebugUnitTest --tests "*LocalMusicTagWriterTest"
```

Expected: tests pass or Android-only write behavior is covered by pure frame-builder tests.

---

### Task 7: Analysis Manager

**Files:**
- Create: `app/src/main/kotlin/com/metrolist/music/localmusic/analysis/LocalMusicAnalysisManager.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/di/AppModule.kt` if provider wiring is required.

**Interfaces:**
- Produces: `val states: StateFlow<Map<String, LocalMusicAnalysisStatus>>`
- Produces: `fun analyze(localSong: LocalSong)`
- Produces: `fun analyzeCurrentIfLocal(songId: String)`

- [ ] **Step 1: Implement queue**

Use `@ApplicationScope CoroutineScope`, `Dispatchers.Default`, and one worker mutex so only one song is analyzed at a time.

- [ ] **Step 2: Implement analyze flow**

Flow:

```text
Queued -> Running -> decode -> mel -> vibenet -> bpm/key -> DB update -> tag write -> Complete
```

On failure:

```text
Queued/Running -> Failed
```

- [ ] **Step 3: Logging and state cleanup**

Use Timber with tag `LocalMusicAnalysis`. Keep `Complete` or `Failed` state briefly enough for UI feedback, then return to `Idle` unless the DB row is now analyzed.

- [ ] **Step 4: Compile**

Run:

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
```

Expected: code compiles.

---

### Task 8: Local Music UI

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicViewModel.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicScreen.kt`

**Interfaces:**
- Consumes: `LocalMusicAnalysisManager.states`
- Consumes: `LocalMusicAnalysisManager.analyze(localSong)`
- Produces UI groups: pending and analyzed local songs.

- [ ] **Step 1: Expose grouped songs**

Add `LocalMusicGroupedSongsState(pendingSongs, analyzedSongs, isLoading)` and split filtered songs by `hasCompleteAnalysis()`.

- [ ] **Step 2: Add long-click menu**

Use Compose combined click:

```kotlin
Modifier.combinedClickable(onClick = onClick, onLongClick = onAnalyze)
```

Show a small dropdown/bottom menu with `立刻分析` for pending or re-analysis-capable local songs.

- [ ] **Step 3: Show analyzing state**

When state is `Queued` or `Running`, show `分析中` in the radar area or chip row and disable duplicate analyze.

- [ ] **Step 4: Keep existing card layout**

Do not reintroduce large card gaps, queue buttons, or cover playback buttons that the user already asked to remove.

- [ ] **Step 5: Compile**

Run:

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
```

Expected: code compiles.

---

### Task 9: Current Playing Song Entry

**Files:**
- Modify: `app/src/main/kotlin/com/metrolist/music/ui/player/Player.kt`
- Modify: `app/src/main/kotlin/com/metrolist/music/ui/menu/PlayerMenu.kt` only if the player menu is the cleanest local entry point.

**Interfaces:**
- Consumes: current local song metadata.
- Consumes: `LocalMusicAnalysisManager.analyzeCurrentIfLocal(songId)`.

- [ ] **Step 1: Locate current local metadata flow**

Use existing player code that already displays BPM/key/emotions for current local music.

- [ ] **Step 2: Add `立刻分析` action**

Show only when the current song has a matching `local_music` row and is not already being analyzed.

- [ ] **Step 3: Compile**

Run:

```powershell
.\gradlew.bat :app:compileFossDebugKotlin
```

Expected: code compiles.

---

### Task 10: Build, Install, And Manual Verification

**Files:**
- No planned source edits.

**Interfaces:**
- Verifies app behavior on the connected phone.

- [ ] **Step 1: Build debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleFossDebug
```

Expected: APK generated.

- [ ] **Step 2: Install**

Run:

```powershell
adb install -r app\build\outputs\apk\foss\debug\app-foss-debug.apk
```

Expected: `Success`.

- [ ] **Step 3: Open app**

Run:

```powershell
adb shell monkey -p com.shine.music.debug 1
```

Expected: app opens.

- [ ] **Step 4: Manual verification**

Verify on device:

- local music splits into pending/analyzed sections;
- pending song long-press shows `立刻分析`;
- analysis produces radar values;
- rescan preserves values from MP3 tags;
- advanced search and similar recommendations see newly analyzed songs.
