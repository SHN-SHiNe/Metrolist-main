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

FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache curl \
    && addgroup -g 1000 shine \
    && adduser -D -u 1000 -G shine shine
WORKDIR /app
COPY --from=server-build --chown=shine:shine /workspace/server/build/install/shine-music-server/ ./
RUN mkdir -p /data /music /cache && chown -R shine:shine /data /music /cache
USER shine
ENV SHINE_HTTP_PORT=8767 \
    SHINE_DATA_DIR=/data \
    SHINE_MUSIC_DIR=/music \
    SHINE_CACHE_DIR=/cache \
    SHINE_TRASH_RETENTION_DAYS=30 \
    SHINE_SCAN_ON_START=true
EXPOSE 8767
VOLUME ["/data", "/music", "/cache"]
HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
  CMD curl --fail --silent http://127.0.0.1:8767/api/health || exit 1
ENTRYPOINT ["/app/bin/shine-music-server"]
