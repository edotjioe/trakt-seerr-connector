# ── Stage 1: build the GraalVM native binary ─────────────────────────────────
FROM ghcr.io/graalvm/native-image-community:21 AS builder

WORKDIR /build

# Install Maven
RUN microdnf install -y maven && microdnf clean all

COPY pom.xml .
# Pre-fetch dependencies (cached layer)
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn -Pnative package -DskipTests -q

# ── Stage 2: minimal runtime image ────────────────────────────────────────────
FROM debian:bookworm-slim

RUN useradd -r -u 1000 app

WORKDIR /app
COPY --from=builder /build/target/trakt-seerr-connector /app/trakt-seerr-connector
RUN chmod +x /app/trakt-seerr-connector

VOLUME ["/config"]

ENV TRAKT_TOKEN_PATH=/config/trakt_tokens.json \
    STATE_DB_PATH=/config/state.sqlite3 \
    SYNC_INTERVAL_SECONDS=3600

COPY docker-entrypoint.sh /app/docker-entrypoint.sh
RUN chmod +x /app/docker-entrypoint.sh

USER app
ENTRYPOINT ["/app/docker-entrypoint.sh"]
