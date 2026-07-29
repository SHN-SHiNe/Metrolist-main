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
COPY --from=web-build /workspace/web/dist web/dist
RUN gradle -p server installDist --no-daemon

FROM golang:1.24-alpine AS sendspin-build
RUN apk add --no-cache gcc musl-dev opus-dev pkgconf
WORKDIR /workspace/sendspin-bridge
COPY sendspin-bridge/go.mod sendspin-bridge/go.sum ./
RUN go mod download
COPY sendspin-bridge/ ./
RUN CGO_ENABLED=1 go build -trimpath -ldflags="-s -w" -o /out/sendspin-bridge . \
    && cp /go/pkg/mod/github.com/\!sendspin/sendspin-go@v1.8.2/LICENSE /out/sendspin-go-LICENSE

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl ffmpeg libopus tini \
    && addgroup -g 1000 shine \
    && adduser -D -u 1000 -G shine shine
WORKDIR /app
COPY --from=server-build --chown=shine:shine /workspace/server/build/install/shine-music-server/ ./
COPY --from=sendspin-build --chown=shine:shine /out/sendspin-bridge /app/bin/sendspin-bridge
COPY --from=sendspin-build --chown=shine:shine /out/sendspin-go-LICENSE /app/licenses/sendspin-go-LICENSE
COPY --from=web-build --chown=shine:shine /workspace/web/node_modules/@sendspin/sendspin-js/LICENSE /app/licenses/sendspin-js-LICENSE
COPY --chown=shine:shine deploy/start-shine.sh /app/bin/start-shine.sh
COPY --chown=shine:shine THIRD_PARTY_NOTICES.md /app/licenses/THIRD_PARTY_NOTICES.md
RUN chmod +x /app/bin/sendspin-bridge /app/bin/start-shine.sh \
    && mkdir -p /data /music /cache && chown -R shine:shine /data /music /cache
USER shine
ENV SHINE_HTTP_PORT=8767 \
    SHINE_DATA_DIR=/data \
    SHINE_MUSIC_DIR=/music \
    SHINE_CACHE_DIR=/cache \
    SHINE_SENDSPIN_BRIDGE_URL=http://127.0.0.1:8936 \
    SHINE_SENDSPIN_CONTROL_ADDR=127.0.0.1:8936 \
    SHINE_SENDSPIN_EVENT_URL=http://127.0.0.1:8767/internal/sendspin/events \
    SHINE_TRASH_RETENTION_DAYS=30 \
    SHINE_SCAN_ON_START=true
EXPOSE 8767
VOLUME ["/data", "/music", "/cache"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8767/api/health || exit 1
ENTRYPOINT ["/sbin/tini", "--", "/app/bin/start-shine.sh"]
