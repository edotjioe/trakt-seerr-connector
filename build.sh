#!/usr/bin/env bash
# Build trakt-seerr-connector using Podman (no local Java/Maven required).
# Usage:
#   ./build.sh          — build fat JAR  (JVM, fast ~60s)
#   ./build.sh native   — build native binary (GraalVM, slow ~10 min)
#   ./build.sh run      — run with fat JAR via Podman

set -euo pipefail

IMAGE="trakt-seerr-connector"
MODE="${1:-jar}"
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Resolve Podman — works both in a Flatpak sandbox and a real terminal.
if [[ -n "${PODMAN:-}" ]]; then
    : # honour explicit override
elif command -v podman &>/dev/null; then
    PODMAN="podman"
elif [[ -x /run/host/usr/bin/podman ]]; then
    PODMAN="/run/host/usr/bin/podman"
elif command -v flatpak-spawn &>/dev/null; then
    # Running inside a Flatpak — escape to host for all podman calls
    PODMAN="flatpak-spawn --host podman"
else
    echo "ERROR: podman not found."
    echo "On Bazzite, open a regular terminal (Konsole/GNOME Terminal) and retry."
    echo "Or install Java 21 on the host:"
    echo "  rpm-ostree install java-21-openjdk-devel maven  # then reboot"
    exit 1
fi

case "$MODE" in

# ── fat JAR (JVM) ──────────────────────────────────────────────────────────────
jar)
    echo "==> Building fat JAR with Maven inside Podman…"
    "$PODMAN" run --rm \
        -v "$PROJECT_DIR":/project:z \
        -w /project \
        docker.io/library/maven:3.9-eclipse-temurin-21 \
        mvn package -DskipTests -q
    echo ""
    echo "Done. Run with:"
    echo "  java -jar target/trakt-seerr-connector-1.0.0.jar sync"
    echo "Or via Podman:"
    echo "  ./build.sh run sync"
    ;;

# ── native binary (GraalVM) ────────────────────────────────────────────────────
native)
    echo "==> Step 1/2: Building fat JAR with Maven…"
    "$PODMAN" run --rm \
        -v "$PROJECT_DIR":/project:z \
        -w /project \
        docker.io/library/maven:3.9-eclipse-temurin-21 \
        mvn package -DskipTests -q

    echo "==> Step 2/2: Running native-image on the fat JAR (~10 min)…"
    # The GraalVM image's entrypoint IS native-image — pass args directly.
    # native-image automatically reads META-INF/native-image/ config from inside the JAR.
    "$PODMAN" run --rm \
        -v "$PROJECT_DIR":/project:z \
        -w /project \
        ghcr.io/graalvm/native-image-community:21 \
        -jar target/trakt-seerr-connector-1.0.0.jar \
        -o target/trakt-seerr-connector

    echo ""
    echo "Done. Native binary at: target/trakt-seerr-connector"
    ;;

# ── run via Podman with the fat JAR ───────────────────────────────────────────
run)
    shift
    CMD="${*:-sync}"
    if [[ ! -f "$PROJECT_DIR/target/trakt-seerr-connector-1.0.0.jar" ]]; then
        echo "No JAR found. Building first…"
        bash "$0" jar
    fi
    ENV_FILE=""
    [[ -f "$PROJECT_DIR/.env" ]] && ENV_FILE="--env-file $PROJECT_DIR/.env"
    CONFIG_MOUNT=""
    [[ -d "$PROJECT_DIR/config" ]] && CONFIG_MOUNT="-v $PROJECT_DIR/config:/config:z"
    # shellcheck disable=SC2086
    "$PODMAN" run --rm -it \
        -v "$PROJECT_DIR/target/trakt-seerr-connector-1.0.0.jar":/app/app.jar:z \
        $CONFIG_MOUNT \
        $ENV_FILE \
        docker.io/library/eclipse-temurin:21-jre-alpine \
        java -jar /app/app.jar $CMD
    ;;

# ── Docker image (native binary in slim container) ────────────────────────────
image)
    echo "==> Building Docker image '$IMAGE' (GraalVM native, ~10 min)…"
    "$PODMAN" build -t "$IMAGE" "$PROJECT_DIR"
    echo "Done. Run with: $PODMAN run --rm --env-file .env -v ./config:/config $IMAGE sync"
    ;;

*)
    echo "Usage: $0 [jar|native|run [args]|image]"
    exit 1
    ;;
esac
