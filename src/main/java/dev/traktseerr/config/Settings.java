package dev.traktseerr.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable application configuration loaded from environment variables (and an optional .env file).
 * Mirrors the Python Settings dataclass in config.py.
 */
public record Settings(
    // --- Trakt ---
    boolean traktSyncEnabled,
    String  traktClientId,
    String  traktClientSecret,
    Path    traktTokenPath,
    int     traktDeviceAuthGraceSeconds,
    int     traktDeviceFirstPollDelaySeconds,

    // --- Seerr ---
    String  seerrBaseUrl,
    String  seerrApiKey,
    String  seerrApiUserId,   // nullable
    boolean seerrVerifySsl,

    // --- MyAnimeList ---
    boolean      malSyncEnabled,
    String       malUsername,
    List<String> malListStatuses,
    String       tmdbApiKey,

    // --- State & behaviour ---
    Path   stateDbPath,
    String tvSeasonsMode,       // "all" or "first"
    int    httpTimeoutSeconds
) {
    private static final String DEFAULT_DIR =
        System.getProperty("user.home") + "/.config/trakt-seerr-connector";

    /** Build Settings by reading environment variables plus an optional .env file in the cwd. */
    public static Settings fromEnv() {
        Map<String, String> env = loadEnv();
        return new Settings(
            parseBool(get(env, "TRAKT_SYNC_ENABLED",                  "false")),
            get(env, "TRAKT_CLIENT_ID",                               ""),
            get(env, "TRAKT_CLIENT_SECRET",                           ""),
            Path.of(get(env, "TRAKT_TOKEN_PATH",                      DEFAULT_DIR + "/trakt_tokens.json")),
            parseInt(get(env, "TRAKT_DEVICE_AUTH_GRACE_SECONDS",      "600")),
            parseInt(get(env, "TRAKT_DEVICE_FIRST_POLL_DELAY_SECONDS","20")),

            get(env, "SEERR_BASE_URL",   ""),
            get(env, "SEERR_API_KEY",    ""),
            get(env, "SEERR_API_USER_ID", null),
            parseBool(get(env, "SEERR_VERIFY_SSL", "true")),

            parseBool(get(env, "MAL_SYNC_ENABLED",    "false")),
            get(env, "MAL_USERNAME",                  ""),
            parseList(get(env, "MAL_LIST_STATUSES",   "watching,plan_to_watch")),
            get(env, "TMDB_API_KEY",                  ""),

            Path.of(get(env, "STATE_DB_PATH", DEFAULT_DIR + "/state.sqlite3")),
            validateTvSeasonsMode(get(env, "TV_SEASONS_MODE", "all")),
            parseInt(get(env, "HTTP_TIMEOUT_SECONDS", "60"))
        );
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Load variables from the real OS environment first, then layer in any keys from a .env file
     * in the current working directory that are not already set in the OS environment.
     */
    private static Map<String, String> loadEnv() {
        Map<String, String> result = new HashMap<>(System.getenv());
        Path dotEnv = Path.of(".env");
        if (!dotEnv.toFile().exists()) return result;
        try {
            for (String line : Files.readAllLines(dotEnv)) {
                line = line.strip();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq < 1) continue;
                String key   = line.substring(0, eq).strip();
                String value = line.substring(eq + 1).strip();
                // Strip surrounding quotes (single or double)
                if (value.length() >= 2) {
                    char first = value.charAt(0), last = value.charAt(value.length() - 1);
                    if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                        value = value.substring(1, value.length() - 1);
                    }
                }
                result.putIfAbsent(key, value);
            }
        } catch (IOException ignored) {}
        return result;
    }

    private static String get(Map<String, String> env, String key, String defaultVal) {
        String v = env.get(key);
        return (v != null) ? v : defaultVal;
    }

    private static boolean parseBool(String val) {
        if (val == null) return false;
        return Set.of("1", "true", "yes", "on").contains(val.strip().toLowerCase());
    }

    private static int parseInt(String val) {
        return Integer.parseInt(val.strip());
    }

    private static List<String> parseList(String val) {
        return Arrays.stream(val.split(","))
            .map(String::strip)
            .filter(s -> !s.isEmpty())
            .toList();
    }

    private static String validateTvSeasonsMode(String val) {
        if (!Set.of("all", "first").contains(val)) {
            throw new IllegalArgumentException(
                "TV_SEASONS_MODE must be 'all' or 'first', got: " + val);
        }
        return val;
    }
}
