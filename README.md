<div align="center">

<img src="./SHiNe.png" alt="SHiNe MUSIC" width="160" />

# SHiNe MUSIC

家庭 NAS 上的共享音乐服务

</div>

SHiNe MUSIC 现在由 NAS 统一管理曲库、在线搜索、下载、收藏、歌单、历史和同步播放房间。手机、平板与电脑无需安装 APK，连接家庭局域网后打开 `http://NAS-IP:8767` 即可使用。

## 当前形态

- Kotlin/JVM 21 + Ktor 单实例服务，SQLite/WAL 持久化共享数据。
- React + TypeScript 响应式 Web：手机端延续原 Android/Compose 的四主入口、迷你播放器与沉浸式全屏播放；PC 端将同一套信息架构展开为左侧导航、内容区、右侧队列和底部播放器。
- 多音频库扫描，可管理 NAS 本地目录、USB 设备和网络挂载；支持 HTTP Range 流媒体、在线歌曲直放与后台下载入库。
- NAS 后台分析 BPM、调性/Camelot 与七维音乐特征，提供相似歌曲、雷达图、高级筛选和保持风格的连续续播；分析只读取三段有界采样，不会随单曲时长持续增加内存占用。
- 共享收藏、歌单与历史；访客免登录，因此局域网内所有用户均有修改权限。
- 多个命名同步房间，以 [Sendspin](https://www.sendspin-audio.com/) 协议、官方 Go 服务端与官方 Web SDK 为同步底座；SHiNe 只负责房间、队列和曲库编排。
- 浅色、深色、纯黑主题，键盘焦点、44px 触控目标和减少动态效果支持。

产品边界与视觉规则分别见 [PRODUCT.md](./PRODUCT.md) 和 [DESIGN.md](./DESIGN.md)。

## 在 NAS 上部署

要求 NAS 已安装 Docker Compose，音乐目录和数据目录对容器 UID/GID `1000:1000` 可读写。下载入库和移入回收区需要音乐目录写权限。

```bash
cp .env.example .env
# 编辑 .env，将 SHINE_*_PATH 改成 NAS 上的绝对路径
docker compose up -d --build
docker compose ps
curl http://127.0.0.1:8767/api/health
```

家庭 NAS 的目标入口为：

```text
http://192.168.31.142:8767
```

首次启动会扫描旧的 `/music` 默认音频库。数据库、配置、回收区与每日备份位于 `SHINE_DATA_PATH`；下载临时文件与封面缓存位于 `SHINE_CACHE_PATH`。删除音乐时先移动至 `data/trash`，默认保留 30 天。

### 多路径与多设备音频库

设置页的“音频库”可添加、改名、停用、单独扫描和选择唯一的下载目标。新增库的容器路径必须位于 `/libraries/<名称>` 下，这个边界防止页面任意读取 NAS 其他文件。

若所有音乐都在一个宿主机父目录下，将 `SHINE_LIBRARIES_PATH` 指向该父目录即可。若音乐分散在不同设备或挂载点，在 `compose.override.yaml` 中逐个映射：

```yaml
services:
  shine-music:
    volumes:
      - /vol1/1000/2.Music:/libraries/nas-main
      - /mnt/usb1/Music:/libraries/usb-1:ro
      - /mnt/media-server/Music:/libraries/media-server:ro
```

重建容器后，在设置页分别添加 `/libraries/nas-main`、`/libraries/usb-1` 和 `/libraries/media-server`。外置或网络设备建议使用 `:ro` 和页面的“只读保护”。库路径失联或挂载意外变空时，SHiNe 会标记“设备离线”并保留原索引；不会把整库曲目当成已删除。如果确实是主动删空了整个目录，再在设置页点击“确认已清空”。

### 环境变量

| 变量 | 默认值 | 用途 |
|---|---:|---|
| `SHINE_HOST_PORT` | `8767` | NAS 对外端口 |
| `SHINE_DATA_PATH` | `./nas-data` | SQLite、配置、备份与回收区 |
| `SHINE_MUSIC_PATH` | `./nas-music` | 音乐文件目录 |
| `SHINE_LIBRARIES_PATH` | `./nas-libraries` | 新增多音频库的宿主机父目录 |
| `SHINE_CACHE_PATH` | `./nas-cache` | 封面、下载临时文件与缓存 |
| `SHINE_TRASH_RETENTION_DAYS` | `30` | 回收区保留天数 |
| `SHINE_SCAN_ON_START` | `true` | 启动时是否增量扫描 |
| `SHINE_ANALYSIS_ON_SCAN` | `true` | 扫描或下载入库后是否在后台补齐音乐分析 |
| `SHINE_VIBENET_MODEL_PATH` | 镜像内置路径 | 可选的 VibeNet ONNX 模型覆盖路径 |
| `SHINE_LOG_LEVEL` | `INFO` | 服务日志级别 |

镜像定义支持 `linux/amd64` 和 `linux/arm64`。容器内由 Ktor 服务和 Sendspin 桥接进程协作，Ktor 同时托管 Web 静态资源并代理 Sendspin WebSocket，因此 NAS 对外仍只有一个容器和一个端口。当前 VibeNet 所用 ONNX Runtime 原生包只在 `linux/amd64` 启用；ARM64 上曲库、播放与同步照常可用，但分析接口会明确返回不可用，不会反复崩溃或重试。

## 使用说明

独立播放状态保存在当前浏览器。收藏、歌单和历史会立即写入 NAS，并对所有访客可见。

进入同步房间后，每台设备仍须点击“加入并启用声音”，浏览器不会在页面打开时自动发声。Sendspin 负责时钟同步、预缓冲、统一时间戳起播和漂移校正；蓝牙、声卡与音响 DSP 的额外缓冲可用每设备 `0–5000 ms` 静态延迟补偿校准。这仍以家庭听感同步为目标，不承诺专业多房间系统的样本级精度。

Sendspin 当前仍标注为 Public Preview。SHiNe 将它封装在独立桥接边界内，并锁定 `sendspin-go v1.8.2` 与 `@sendspin/sendspin-js 3.2.1`，后续升级需先执行多设备回归。协议说明见 [Sendspin Protocol](https://www.sendspin-audio.com/spec/)，客户端实现见 [sendspin-js](https://github.com/Sendspin/sendspin-js)。

当前 HTTP 局域网入口按普通 Web 验收。Manifest 和图标已经提供，但完整 PWA 安装、Service Worker、离线能力以及网页选择音频输出设备，需要未来配置受信任 HTTPS 后再启用。

## API

主要接口位于：

- `/api/library`、`/api/libraries`、`/api/search`、`/api/media/{trackId}/stream`
- `/api/analysis`、`/api/library/{trackId}/similar`、`/api/library/advanced-search`、`/api/radio/next`
- `/api/playlists`、`/api/favorites`、`/api/history`
- `/api/downloads`、`/api/scans`、`/api/settings/sources`
- `/api/rooms`、`/api/rooms/{roomId}/state`、`/api/rooms/{roomId}/sendspin`、`/api/health`

音源密钥只保存在服务端，读取配置时返回脱敏值。流媒体支持 Range 请求、正确 Content-Type 和缓存头。

## 本地开发与测试

Web 客户端：

```bash
cd web
npm ci
npm test
npm run typecheck
npm run build
npm run e2e
```

服务端（需要 JDK 21、Gradle 9.4.1、`ffmpeg`/`ffprobe`；VibeNet 推理还需要 x86_64）：

```bash
gradle -p server test installDist
server/build/install/shine-music-server/bin/shine-music-server
```

Sendspin 桥接进程（需要 Go 1.24、libopus 开发文件和 `ffmpeg`）：

```bash
cd sendspin-bridge
go test ./...
go build ./...
```

开发时可用 `npm run dev` 启动 Vite。仅运行 JVM 时会使用内存桥接替身，房间 REST API 可用但不会输出同步音频；完整联调建议使用 Compose。手动分别启动两个进程时，除 `SHINE_SENDSPIN_BRIDGE_URL=http://127.0.0.1:8936` 外，还要为两边设置相同的随机 `SHINE_SENDSPIN_INTERNAL_TOKEN`，并为桥接进程设置 `SHINE_SENDSPIN_EVENT_URL=http://127.0.0.1:8767/internal/sendspin/events`。生产构建必须先生成 `web/dist`，Gradle 会将其嵌入服务端发行包。

## Android 历史代码

原 Android/Compose App 保留为手机 Web 的产品与视觉金标准，不再进入默认发布流程，也不会继续作为 NAS 客户端开发。旧工作流只能手动触发；现有 Android 数据库不会迁移到 NAS。16 张基准截图位于 `fastlane/metadata/android/en-US/images/screenshots`。

仓库沿用 Android 代码中已有的 VibeNet/EfficientNet 模型供私人 NAS 部署。ONNX Runtime 使用 MIT 许可；模型原始发布来源与独立许可尚未在历史仓库中记录，若要把镜像公开分发给第三方，应先完成模型来源与许可审计，详见 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。

## 安全边界

这是为可信家庭局域网设计的免登录服务。所有访客都可以修改歌单、收藏、音源配置、发起下载，以及将音乐移入回收区。不要直接暴露到公网；若需要远程访问，应先增加身份认证、HTTPS 和访问控制。
