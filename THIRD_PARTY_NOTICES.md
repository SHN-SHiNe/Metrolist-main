# Third-party notices

SHiNe MUSIC integrates the following Sendspin projects as dependencies:

- `github.com/Sendspin/sendspin-go` v1.8.2 — Apache License 2.0
- `@sendspin/sendspin-js` v3.2.1 — Apache License 2.0

Project source and license text are available from the [Sendspin code directory](https://www.sendspin-audio.com/code/) and the corresponding package repositories. SHiNe MUSIC does not vendor or modify their source code; the versions above are resolved by Go modules and npm during the build.

The NAS analysis worker also uses:

- `com.microsoft.onnxruntime:onnxruntime` v1.27.0 — MIT License. The runtime is resolved from Maven Central and is used for CPU inference on supported JVM/Linux platforms.
- `app/src/main/assets/vibenet/efficientnet_model.onnx` — the VibeNet/EfficientNet model already distributed with the legacy SHiNe MUSIC Android source. Its original upstream package and standalone model license were not recorded in this repository; public redistribution outside the existing SHiNe MUSIC project requires a separate provenance/license audit.

The private home-NAS deployment reuses the repository's existing model asset; it does not upload music or analysis data to a third-party inference service.
