# 本地音乐崩溃与卡顿修复

## 问题现象

1. **播放本地音乐时应用崩溃**（OOM）
2. **进入本地音乐页面加载极度卡顿**（ANR）
3. 物理设备（小米 23127PN0CC, Android 14）复现必崩

## 根因分析

### 崩溃：OutOfMemoryError

通过 `adb logcat` 捕获到完整 Java 堆栈：

```
java.lang.OutOfMemoryError: Failed to allocate a 24 byte allocation with 65496 free bytes
  and 63KB until OOM, target footprint 268435456, growth limit 268435456;
  giving up on allocation because <1% of heap free after GC.

at java.util.HashMap.put(HashMap.java:608)
at androidx.media3.exoplayer.PlaylistTimeline.<init>(PlaylistTimeline.java:65)
at androidx.media3.exoplayer.ExoPlayerImplInternal.setMediaItemsInternal(ExoPlayerImplInternal.java:990)
```

**触发点**：`LocalMusicScreen.kt:260`

```kotlin
items = filteredSongs.map { it.song.toMediaItem(it.localMusic.contentUri) }
```

点击播放时，将**整个本地音乐列表**（可能数千首）一次性 `.map { toMediaItem() }` 传给 ExoPlayer。ExoPlayer 在 `PlaylistTimeline` 内部为每首歌创建 HashMap 条目，堆耗尽 256MB 上限 → OOM → CrashHandler 杀进程。

### 卡顿：GC 风暴 + 主线程阻塞

通过 `adb dumpsys dropbox` 捕获到多次 ANR：

```
Subject: Input dispatching timed out (... is not responding. Waited 5000ms)
142% CPU, 767894 minor faults, RssKb: 489352
```

logcat 中反复出现：
```
Starting a blocking GC Alloc
WaitForGcToComplete blocked Alloc on Alloc
```

三重叠加导致主线程被阻塞：

1. **主线程排序**：`LocalMusicViewModel.kt:88-98` — `combine` transform 中的 `songs.sorted()` 使用 `Collator` 在 `viewModelScope`（Dispatchers.Main）上对整个列表排序
2. **主线程过滤**：`LocalMusicScreen.kt:119-133` — `filteredSongs` 在 Composable 的 `remember{}` 中对整个列表执行字符串过滤
3. **N+1 查询**（已存在，本次未修）：`localSongs()` 返回 `List<LocalSong>`，`LocalSong` 有 `@Relation -> Song`，`Song` 又有 4 个 `@Relation`（artists/artistMaps/album/format），Room 为每首歌执行多次查询，大量对象分配触发频繁 GC

## 修复内容

### 修复 1：限制 ExoPlayer 播放队列大小（修复 OOM 崩溃）

**文件**：`app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicScreen.kt`

新增队列大小限制和窗口辅助函数：

```kotlin
private const val MAX_PLAY_QUEUE_SIZE = 500

private fun List<LocalSong>.toPlayQueue(startIndex: Int): Pair<List<MediaItem>, Int> {
    if (size <= MAX_PLAY_QUEUE_SIZE) {
        return map { it.song.toMediaItem(it.localMusic.contentUri) } to startIndex
    }
    val windowStart = startIndex.coerceAtMost(size - MAX_PLAY_QUEUE_SIZE)
    val windowEnd = windowStart + MAX_PLAY_QUEUE_SIZE
    val items = subList(windowStart, windowEnd).map { it.song.toMediaItem(it.localMusic.contentUri) }
    return items to (startIndex - windowStart)
}
```

- **点击播放**：队列超过 500 首时，从点击位置取 500 首窗口，`startIndex` 相应调整
- **随机播放**：`filteredSongs.shuffled().take(MAX_PLAY_QUEUE_SIZE)` 只取 500 首

### 修复 2：排序移到后台线程（修复 GC 风暴卡顿）

**文件**：`app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicViewModel.kt`

`localSongs` flow 加 `.flowOn(Dispatchers.Default)`：

```kotlin
val localSongs =
    combine(...) { songs, sort ->
        songs.sorted(sortType, descending)
    }.flowOn(Dispatchers.Default)           // ← 新增
     .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
```

### 修复 3：过滤移到 ViewModel 后台线程（修复主线程过滤卡顿）

**文件**：`LocalMusicViewModel.kt` + `LocalMusicScreen.kt`

将过滤逻辑从 Composable `remember{}` 移到 ViewModel Flow：

```kotlin
// LocalMusicViewModel.kt — 新增 filteredSongs flow
val filteredSongs =
    combine(localSongs, debouncedSearchQuery) { songs, query ->
        if (query.isBlank()) songs
        else {
            val normalized = query.normalizeForSearch()
            songs.filter { /* 相同过滤逻辑 */ }
        }
    }.flowOn(Dispatchers.Default)
     .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())
```

```kotlin
// LocalMusicScreen.kt — 改为从 VM collect
val filteredSongs by viewModel.filteredSongs.collectAsStateWithLifecycle()
```

清理了 Screen 中不再使用的变量（`localSongs`、`debouncedSearchQuery`、`normalizedQuery`）和对应 import。

## 改动文件清单

| 文件 | 改动说明 |
|---|---|
| `app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicScreen.kt` | 队列限 500 首、过滤移到 VM、清理无用变量和 import |
| `app/src/main/kotlin/com/metrolist/music/ui/screens/localmusic/LocalMusicViewModel.kt` | 排序/过滤加 `flowOn(Dispatchers.Default)`、新增 `filteredSongs` flow |

## 行为保持不变

- 点击歌曲仍从点击位置开始播放（窗口队列保持正确 startIndex）
- 随机播放仍随机（取前 500 首随机结果）
- 搜索过滤逻辑不变（仅执行线程变更）
- 排序逻辑不变（Collator、所有排序类型）

## 已知未修复项（超出本次范围）

- **N+1 查询**：`localSongs()` 的 `@Relation` 嵌套（LocalSong -> Song -> artists/artistMaps/album/format）仍有 N+1 问题，大库加载时仍有一定开销
- **SAF 扫描慢**：`LocalMusicScanner` 使用 DocumentsContract 递归遍历目录树，每次目录查询为 binder 调用，扫描速度受限于 SAF
- **封面图全尺寸写入**：`LocalMusicTagReader.writeArtwork()` 将 `embeddedPicture` 全尺寸写入磁盘，大封面 FLAC 文件有 OOM 风险

## 验证方式

```bash
# 编译安装
.\gradlew.bat :app:installFossDebug

# 监控崩溃
adb logcat -b crash -d

# 监控 ANR
adb shell "dumpsys dropbox --print" | grep -i "shine.music"
```
