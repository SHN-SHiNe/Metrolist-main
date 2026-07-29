FROM node:24-alpine AS web-build
WORKDIR /workspace/web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM gradle:9.4.1-jdk21 AS server-build
WORKDIR /workspace
COPY gradle/ gradle/
COPY server/ server/
COPY chinamusic/ chinamusic/
COPY app/src/main/assets/vibenet/efficientnet_model.onnx app/src/main/assets/vibenet/efficientnet_model.onnx
COPY --from=web-build /workspace/web/dist web/dist
RUN gradle -p server installDist --no-daemon

FROM golang:1.24-bullseye AS sendspin-build
ARG GOPROXY=https://proxy.golang.org,direct
ARG DEBIAN_MIRROR=
ENV GOPROXY=${GOPROXY}
RUN if [ -n "$DEBIAN_MIRROR" ]; then \
        sed -i "s#http://deb.debian.org/debian#$DEBIAN_MIRROR#g; s#http://security.debian.org/debian-security#$DEBIAN_MIRROR-security#g" /etc/apt/sources.list; \
    fi \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends build-essential libopus-dev libopusfile-dev pkg-config ca-certificates \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /workspace/sendspin-bridge
COPY sendspin-bridge/go.mod sendspin-bridge/go.sum ./
RUN go mod download
COPY sendspin-bridge/ ./
RUN CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/sendspin-bridge . \
    && cp /go/pkg/mod/github.com/\!sendspin/sendspin-go@v1.8.2/LICENSE /out/sendspin-go-LICENSE

FROM eclipse-temurin:21-jre-jammy
ARG UBUNTU_MIRROR=
RUN if [ -n "$UBUNTU_MIRROR" ]; then \
        sed -i "s#http://archive.ubuntu.com/ubuntu#$UBUNTU_MIRROR#g; s#http://security.ubuntu.com/ubuntu#$UBUNTU_MIRROR#g" /etc/apt/sources.list; \
    fi \
    && apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends curl ffmpeg libopus0 libopusfile0 tini \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 1000 shine \
    && useradd --uid 1000 --gid shine --create-home --shell /usr/sbin/nologin shine
WORKDIR /app
COPY --from=server-build --chown=shine:shine /workspace/server/build/install/shine-music-server/ ./
COPY --from=server-build --chown=shine:shine /workspace/app/src/main/assets/vibenet/efficientnet_model.onnx /app/models/vibenet/efficientnet_model.onnx
COPY --from=sendspin-build --chown=shine:shine /out/sendspin-bridge /app/bin/sendspin-bridge
COPY --from=sendspin-build --chown=shine:shine /out/sendspin-go-LICENSE /app/licenses/sendspin-go-LICENSE
COPY --from=web-build --chown=shine:shine /workspace/web/node_modules/@sendspin/sendspin-js/LICENSE /app/licenses/sendspin-js-LICENSE
COPY --chown=shine:shine deploy/start-shine.sh /tmp/start-shine.sh
COPY --chown=shine:shine THIRD_PARTY_NOTICES.md /app/licenses/THIRD_PARTY_NOTICES.md
RUN cp /tmp/start-shine.sh /app/bin/start-shine.sh \
    && sed -i 's/\r$//' /app/bin/start-shine.sh \
    && chown shine:shine /app/bin/start-shine.sh \
    && chmod 0755 /app/bin/start-shine.sh \
    && rm /tmp/start-shine.sh \
    && chmod +x /app/bin/sendspin-bridge \
    && mkdir -p /data /music /libraries /cache && chown -R shine:shine /data /music /libraries /cache
USER shine
ENV SHINE_HTTP_PORT=8767 \
    SHINE_DATA_DIR=/data \
    SHINE_MUSIC_DIR=/music \
    SHINE_LIBRARY_DIR=/libraries \
    SHINE_CACHE_DIR=/cache \
    SHINE_SENDSPIN_BRIDGE_URL=http://127.0.0.1:8936 \
    SHINE_SENDSPIN_CONTROL_ADDR=127.0.0.1:8936 \
    SHINE_SENDSPIN_EVENT_URL=http://127.0.0.1:8767/internal/sendspin/events \
    SHINE_TRASH_RETENTION_DAYS=30 \
    SHINE_SCAN_ON_START=true \
    SHINE_ANALYSIS_ON_SCAN=true
EXPOSE 8767
VOLUME ["/data", "/music", "/libraries", "/cache"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8767/api/health || exit 1
ENTRYPOINT ["/usr/bin/tini", "--", "/app/bin/start-shine.sh"]
