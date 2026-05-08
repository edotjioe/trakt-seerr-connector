# trakt-seerr-connector

A GraalVM native CLI that syncs your **Trakt** watchlist and **MyAnimeList** anime list to [Seerr](https://github.com/sct/overseerr) (Overseerr / Jellyseerr) as media requests.

Java port of [trakt-seerr-sync](https://github.com/example/trakt-seerr-sync), compiled to a self-contained native binary — no JVM required at runtime.

---

## Quick start

### 1. Configure

```bash
cp env.example .env
# Edit .env with your credentials
```

Minimum required variables:

```env
# At least one source must be enabled:
TRAKT_SYNC_ENABLED=true
TRAKT_CLIENT_ID=...
TRAKT_CLIENT_SECRET=...

# Destination (always required):
SEERR_BASE_URL=https://seerr.yourhome:5055
SEERR_API_KEY=...
```

See [env.example](env.example) for all options.

---

### 2. Build

> **Bazzite / immutable Fedora users:** run these commands from a real terminal (Konsole, GNOME Terminal), not from inside a Flatpak shell.

#### Option A — native binary (recommended, standalone, no JVM needed at runtime)

Requires [Podman](https://podman.io/). Pulls two container images on the first run (~1 GB total).

```bash
./build.sh native
# binary lands at: target/trakt-seerr-connector
```

#### Option B — fat JAR (JVM, faster to build)

```bash
./build.sh jar
# jar lands at: target/trakt-seerr-connector-1.0.0.jar
```

#### Option C — local Maven + GraalVM (no Podman needed)

```bash
# fat JAR only (any Java 21 JDK):
mvn package -DskipTests

# native binary (requires GraalVM 21 with native-image on PATH):
mvn -Pnative package -DskipTests
```

---

### 3. Authorize Trakt (once)

Before the first sync, authenticate with Trakt via device flow:

```bash
# native binary:
./target/trakt-seerr-connector auth

# fat JAR:
java -jar target/trakt-seerr-connector-1.0.0.jar auth

# via build.sh (uses Podman):
./build.sh run auth
```

Open the printed URL, enter the displayed code, then press Enter. Tokens are saved to `~/.config/trakt-seerr-connector/trakt_tokens.json` (or `TRAKT_TOKEN_PATH`).

---

### 4. Run a sync

```bash
# native binary:
./target/trakt-seerr-connector sync

# fat JAR:
java -jar target/trakt-seerr-connector-1.0.0.jar sync

# via build.sh (uses Podman — loads .env automatically):
./build.sh run sync
```

#### Flags

| Flag | Description |
|------|-------------|
| `--dry-run` | Log what would be requested without calling Seerr |
| `--force` | Re-process items that were already synced |

```bash
./target/trakt-seerr-connector sync --dry-run
./target/trakt-seerr-connector sync --force
```

#### Exit codes

| Code | Meaning |
|------|---------|
| `0` | All items processed successfully |
| `1` | Configuration error (no source enabled, missing API key) |
| `2` | Sync ran but one or more Seerr requests failed |

---

## Subcommands

```
trakt-seerr-connector auth         One-time Trakt device-flow OAuth
trakt-seerr-connector sync         Sync watchlists to Seerr
trakt-seerr-connector clear-state  Delete the deduplication database
```

```bash
./target/trakt-seerr-connector --help
./target/trakt-seerr-connector sync --help
```

---

## Docker / Podman (continuous sync)

Runs the sync on a configurable interval (default: every hour).

```bash
mkdir config
cp env.example .env   # fill in credentials

# Build the image (GraalVM native inside a slim Debian container):
./build.sh image

# Start:
podman compose up -d        # or: docker compose up -d

# Logs:
podman compose logs -f

# One-time auth from inside the running container:
podman exec -it trakt-seerr-connector /app/trakt-seerr-connector auth
```

The `/config` volume persists the Trakt token and the SQLite deduplication database across restarts.

---

## Configuration reference

All settings are read from environment variables or a `.env` file in the working directory.

### Trakt

| Variable | Default | Description |
|----------|---------|-------------|
| `TRAKT_SYNC_ENABLED` | `false` | Enable Trakt source |
| `TRAKT_CLIENT_ID` | — | Trakt API client ID |
| `TRAKT_CLIENT_SECRET` | — | Trakt API client secret |
| `TRAKT_TOKEN_PATH` | `~/.config/trakt-seerr-connector/trakt_tokens.json` | OAuth token file |
| `TRAKT_DEVICE_AUTH_GRACE_SECONDS` | `600` | Extra seconds before device code expiry |
| `TRAKT_DEVICE_FIRST_POLL_DELAY_SECONDS` | `20` | Delay before first poll during auth |

### Seerr

| Variable | Default | Description |
|----------|---------|-------------|
| `SEERR_BASE_URL` | — | e.g. `https://seerr.yourhome:5055` |
| `SEERR_API_KEY` | — | Settings → API key in Seerr UI |
| `SEERR_API_USER_ID` | — | Post requests as a specific user ID (optional) |
| `SEERR_VERIFY_SSL` | `true` | Set `false` for self-signed certificates |

### MyAnimeList

| Variable | Default | Description |
|----------|---------|-------------|
| `MAL_SYNC_ENABLED` | `false` | Enable MAL source |
| `MAL_USERNAME` | — | Your MAL username |
| `MAL_LIST_STATUSES` | `watching,plan_to_watch` | Comma-separated statuses to sync |
| `TMDB_API_KEY` | — | Required for MAL (maps anime → TMDB IDs) |

Valid `MAL_LIST_STATUSES` values: `watching`, `plan_to_watch`, `completed`, `on_hold`, `dropped`

### Behaviour

| Variable | Default | Description |
|----------|---------|-------------|
| `TV_SEASONS_MODE` | `all` | `all` = all seasons, `first` = season 1 only |
| `HTTP_TIMEOUT_SECONDS` | `60` | HTTP request timeout |
| `STATE_DB_PATH` | `~/.config/trakt-seerr-connector/state.sqlite3` | Deduplication database |
| `SYNC_INTERVAL_SECONDS` | `3600` | Docker only — seconds between syncs |

---

## How deduplication works

Each successfully processed item (movie or TV show, keyed by TMDB ID) is recorded in a local SQLite database. On subsequent runs, already-processed items are skipped unless `--force` is passed.

Recorded statuses:

| Status | Meaning |
|--------|---------|
| `requested` | Successfully submitted to Seerr |
| `duplicate` | Seerr returned 409 (already requested) |
| `error_forbidden` | Seerr returned 403 |
| `error` | Any other failure |

Reset the database:
```bash
./target/trakt-seerr-connector clear-state
```

---

## Project structure

```
src/main/java/dev/traktseerr/
├── Main.java
├── cli/          AuthCommand, SyncCommand, ClearStateCommand, MainCommand
├── client/       TraktClient, SeerrClient, MalClient, HttpHelper
├── config/       Settings  (env vars + .env loader)
├── model/        MediaItem, TraktTokens
├── state/        StateManager  (SQLite)
└── sync/         SyncRunner
```

Built with Java 21, [Picocli](https://picocli.info/), [Jackson](https://github.com/FasterXML/jackson), [sqlite-jdbc](https://github.com/xerial/sqlite-jdbc), and the [GraalVM Native Image Maven Plugin](https://graalvm.github.io/native-build-tools/latest/maven-plugin.html).
