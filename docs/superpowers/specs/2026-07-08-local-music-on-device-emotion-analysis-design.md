# Local Music On-Device Emotion Analysis Design

## Goal

Implement a local-music analysis loop inside the Android app:

- local scanning classifies songs as analyzed or pending analysis;
- pending local songs can be analyzed from the app;
- analysis runs on the phone with the vibenet ONNX model;
- results are saved to the app database and, when possible, written back to MP3 ID3 tags.

This feature only applies to real local files. Network streams and playback/offline caches are not analysis targets.

## Current Context

The existing local music path already reads ID3 analysis tags through `LocalMusicTagReader` and stores them in `local_music` fields:

- `bpm`
- `keyName`
- `valence`
- `energy`
- `danceability`
- `acousticness`
- `instrumentalness`
- `liveness`
- `speechiness`
- `moodSummary`

The `musicanalyzer` project writes compatible MP3 tags:

- `TBPM` for BPM;
- `TKEY` for musical key;
- `TXXX:VALENCE`, `TXXX:ENERGY`, `TXXX:DANCEABILITY`, `TXXX:ACOUSTICNESS`, `TXXX:INSTRUMENTALNESS`, `TXXX:LIVENESS`, `TXXX:SPEECHINESS`;
- summary mood text in `TMOO`, `TXXX:WM/Mood`, `TXXX:MOOD`, and `COMM::xxx`.

The vibenet repository ships `efficientnet_model.onnx` at about 18.5 MB. The Python package feeds the model a 16 kHz mono waveform transformed into a 128-bin mel spectrogram and reads seven outputs:

- acousticness
- danceability
- energy
- instrumentalness
- liveness
- speechiness
- valence

## Design

### Analysis Classification

A local song is `analyzed` only when all required analysis fields are available:

- `bpm` is non-null and greater than zero;
- `keyName` is non-blank;
- all seven vibenet emotion fields are non-null.

A song is `pending analysis` when any required field is missing. The local music page will display separate groups for analyzed and pending songs. Existing radar components should keep showing `待分析` for songs without complete emotion data.

Scanning remains fast: it reads existing tags and updates classification, but it does not run ONNX inference during the scan.

### User Entry Points

The first implementation supports single-song analysis:

- long-press a local music card;
- show a menu item named `立刻分析` when the song is local and not already being analyzed;
- show the same action for the currently playing local song from the player menu or player-local-info area;
- show an analyzing state while work is in progress;
- update the row when analysis completes.

Batch analysis can be added later on top of the same queue, but is outside the first implementation unless the queue and UI prove simple enough to expose safely.

### Analysis Pipeline

Add `LocalMusicAnalysisManager` as the orchestration layer. It owns:

- one-at-a-time analysis queue;
- in-memory per-song analysis state;
- duplicate request suppression;
- DB update after successful analysis;
- optional MP3 tag write after DB update.

Add `VibenetOnDeviceAnalyzer` for model inference. It owns:

- loading `efficientnet_model.onnx` from app assets;
- creating a single ONNX Runtime session;
- running inference on a mel spectrogram;
- mapping outputs to the app's emotion field names.

Add an audio preprocessing unit that owns:

- opening a local `contentUri`;
- decoding supported audio formats to PCM;
- mixing to mono;
- resampling to 16 kHz;
- creating the vibenet-compatible mel spectrogram with:
  - `n_fft = 1024`
  - `hop_length = 320`
  - `win_length = 640`
  - `n_mels = 128`
  - `fmin = 0`
  - `fmax = 8000`
  - Hann window
  - power-to-dB with `top_db = 80`
  - no centering.

The app should run this on a background dispatcher. UI state must never wait on the main thread.

### Result Persistence

Add a DAO method that updates all analysis fields for one local song in a single DB operation.

After DB persistence, try to write tags back to the file:

- MP3 files: write ID3 tags in the musicanalyzer-compatible format.
- non-MP3 or non-writable SAF locations: keep the DB result and report that file tag writing was skipped.

DB persistence is the source of truth for app behavior. MP3 tag writing is best-effort so analysis is not lost when a file cannot be modified.

### ID3 Tag Writer

Add `LocalMusicTagWriter` rather than extending the reader with write behavior. It should:

- only support MP3 in the first implementation;
- preserve unrelated ID3 frames;
- replace managed analysis frames before writing new values;
- write `TBPM` and `TKEY`;
- write seven `TXXX:*` emotion frames;
- write summary text to `TMOO`, `TXXX:WM/Mood`, `TXXX:MOOD`, and `COMM::xxx`;
- save in an ID3 version compatible with the existing musicanalyzer convention where feasible.

The writer should degrade gracefully on SAF write errors and return a structured success/skipped/error result.

### BPM And Key

The goal requires the emotion loop, but analyzed classification also needs BPM and key. The implementation must produce BPM and key when those values are missing:

- preserve BPM/key from existing tags when present;
- calculate BPM from the decoded PCM using onset/beat analysis;
- calculate key from chroma-style pitch-class energy and major/minor templates compatible with the existing musicanalyzer approach;
- include BPM/key in the same persisted analysis result as the vibenet emotion values.

The final goal is not complete until the app can produce all required analyzed fields for a pending song.

### Error Handling

Expected failures:

- unsupported audio format;
- decode failure;
- ONNX model load failure;
- insufficient memory;
- SAF read/write permission loss;
- tag write unsupported for non-MP3.

The UI should show a short failure message and keep the song pending. Partial results should not mark a song analyzed.

### Testing And Verification

Build verification:

- run `.\gradlew.bat :app:assembleFossDebug` or the requested release build;
- install to the connected phone;
- open the app.

Manual verification:

- scan local music and confirm songs split into analyzed and pending groups;
- long-press a pending MP3 and run `立刻分析`;
- confirm the card changes from `待分析` to radar values;
- confirm advanced search and similar recommendations can use the new values;
- rescan the same MP3 and confirm the values are read from tags and survive app restart;
- test a non-MP3 file and confirm DB analysis works even when tag writing is skipped.
