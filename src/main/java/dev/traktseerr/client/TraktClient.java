package dev.traktseerr.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.traktseerr.config.Settings;
import dev.traktseerr.model.MediaItem;
import dev.traktseerr.model.TraktTokens;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Trakt.tv API client.
 * Handles device-flow OAuth, token persistence, token refresh, and watchlist pagination.
 * Mirrors trakt_client.py.
 */
public class TraktClient {

    private static final String BASE_URL = "https://api.trakt.tv";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Settings   cfg;
    private final HttpHelper http;

    public TraktClient(Settings cfg) {
        this.cfg  = cfg;
        // Trakt always uses valid TLS
        this.http = new HttpHelper(cfg.httpTimeoutSeconds(), true);
    }

    // -------------------------------------------------------------------------
    // Token persistence
    // -------------------------------------------------------------------------

    public TraktTokens loadTokens() throws IOException {
        Path p = cfg.traktTokenPath();
        if (!p.toFile().exists()) return null;
        JsonNode node = MAPPER.readTree(p.toFile());
        return new TraktTokens(
            node.get("access_token").asText(),
            node.get("refresh_token").asText(),
            node.get("expires_at").asLong()
        );
    }

    public void saveTokens(TraktTokens tokens) throws IOException {
        Path p = cfg.traktTokenPath();
        if (p.getParent() != null) p.getParent().toFile().mkdirs();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("access_token",  tokens.accessToken());
        node.put("refresh_token", tokens.refreshToken());
        node.put("expires_at",    tokens.expiresAt());
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(p.toFile(), node);
        try {
            Files.setPosixFilePermissions(p, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {} // non-POSIX filesystems
    }

    // -------------------------------------------------------------------------
    // OAuth device flow
    // -------------------------------------------------------------------------

    public void deviceAuthorize() throws Exception {
        String codeUrl  = BASE_URL + "/oauth/device/code";
        String tokenUrl = BASE_URL + "/oauth/device/token";
        Map<String, String> hdrs = traktHeaders();

        // Step 1 — request device code
        ObjectNode body = MAPPER.createObjectNode().put("client_id", cfg.traktClientId());
        HttpHelper.Response codeResp = http.post(codeUrl, MAPPER.writeValueAsString(body), hdrs);
        if (codeResp.statusCode() != 200) {
            throw new RuntimeException("Failed to get device code: HTTP " + codeResp.statusCode());
        }
        JsonNode codeJson   = codeResp.json();
        String deviceCode   = codeJson.get("device_code").asText();
        String userCode     = codeJson.get("user_code").asText();
        String verifyUrl    = codeJson.get("verification_url").asText();
        int    expiresIn    = codeJson.get("expires_in").asInt();
        int    interval     = codeJson.get("interval").asInt(5);

        System.out.println("Open this URL and enter the code below:");
        System.out.println("  " + verifyUrl);
        System.out.println("  Code: " + userCode);

        long deadline = Instant.now().getEpochSecond()
                        + expiresIn + cfg.traktDeviceAuthGraceSeconds();
        Thread.sleep(cfg.traktDeviceFirstPollDelaySeconds() * 1000L);

        // Step 2 — poll for approval
        ObjectNode pollBody = MAPPER.createObjectNode()
            .put("code",          deviceCode)
            .put("client_id",     cfg.traktClientId())
            .put("client_secret", cfg.traktClientSecret());

        while (Instant.now().getEpochSecond() < deadline) {
            HttpHelper.Response resp = http.post(tokenUrl, MAPPER.writeValueAsString(pollBody), hdrs);
            int status = resp.statusCode();

            if (status == 200) {
                JsonNode j      = resp.json();
                long expiresAt  = Instant.now().getEpochSecond() + j.get("expires_in").asLong();
                TraktTokens tok = new TraktTokens(
                    j.get("access_token").asText(),
                    j.get("refresh_token").asText(),
                    expiresAt
                );
                saveTokens(tok);
                System.out.println("Authorisation successful. Tokens saved to " + cfg.traktTokenPath());
                return;
            } else if (status == 429) {
                // Rate-limited — double the interval
                Thread.sleep(interval * 2000L);
                continue;
            } else if (status == 400) {
                JsonNode j = resp.json();
                String err = j.has("error") ? j.get("error").asText() : "unknown";
                switch (err) {
                    case "authorization_pending" -> { /* keep polling */ }
                    case "slow_down"             -> interval += 5;
                    case "expired_token"         -> throw new RuntimeException("Device code expired.");
                    case "access_denied"         -> throw new RuntimeException("Access denied by user.");
                    default -> throw new RuntimeException("Unexpected error during device poll: " + err);
                }
            } else if (status >= 500) {
                System.err.println("WARN: Trakt server error " + status + ", retrying…");
            } else {
                throw new RuntimeException("Unexpected response " + status + " during device poll");
            }
            Thread.sleep(interval * 1000L);
        }
        throw new RuntimeException("Device authorisation timed out.");
    }

    // -------------------------------------------------------------------------
    // Token refresh
    // -------------------------------------------------------------------------

    public TraktTokens refreshTokens(TraktTokens tokens) throws Exception {
        String url = BASE_URL + "/oauth/token";
        ObjectNode body = MAPPER.createObjectNode()
            .put("refresh_token", tokens.refreshToken())
            .put("client_id",     cfg.traktClientId())
            .put("client_secret", cfg.traktClientSecret())
            .put("grant_type",    "refresh_token");
        HttpHelper.Response resp = http.post(url, MAPPER.writeValueAsString(body), traktHeaders());
        if (resp.statusCode() != 200) {
            throw new RuntimeException("Token refresh failed: HTTP " + resp.statusCode());
        }
        JsonNode j      = resp.json();
        long expiresAt  = Instant.now().getEpochSecond() + j.get("expires_in").asLong();
        return new TraktTokens(
            j.get("access_token").asText(),
            j.get("refresh_token").asText(),
            expiresAt
        );
    }

    /**
     * Load tokens from disk, refresh if expiring within 24 h, and return valid tokens.
     */
    public TraktTokens ensureValidTokens() throws Exception {
        TraktTokens tokens = loadTokens();
        if (tokens == null) {
            throw new IllegalStateException(
                "No Trakt tokens found. Run 'trakt-seerr-connector auth' first.");
        }
        long in24h = Instant.now().getEpochSecond() + 86_400;
        if (tokens.expiresAt() < in24h) {
            System.out.println("Refreshing Trakt tokens…");
            tokens = refreshTokens(tokens);
            saveTokens(tokens);
        }
        return tokens;
    }

    // -------------------------------------------------------------------------
    // Watchlist iteration
    // -------------------------------------------------------------------------

    /**
     * Returns all movies and TV shows from the authenticated user's Trakt watchlist.
     */
    public List<MediaItem> fetchWatchlist(TraktTokens tokens) throws Exception {
        List<MediaItem> items = new ArrayList<>();
        items.addAll(fetchWatchlistPage("movies", tokens));
        items.addAll(fetchWatchlistPage("shows",  tokens));
        return items;
    }

    private List<MediaItem> fetchWatchlistPage(String type, TraktTokens tokens) throws Exception {
        String mediaType = "movies".equals(type) ? "movie" : "tv";
        List<MediaItem> items = new ArrayList<>();
        int page = 1;
        int totalPages = 1;

        while (page <= totalPages) {
            String url = BASE_URL + "/users/me/watchlist/" + type
                         + "?extended=full&page=" + page + "&limit=100";
            Map<String, String> hdrs = Map.of(
                "Authorization",  "Bearer " + tokens.accessToken(),
                "trakt-api-key",  cfg.traktClientId(),
                "trakt-api-version", "2"
            );
            HttpHelper.Response resp = http.get(url, hdrs);
            if (resp.statusCode() != 200) {
                System.err.println("WARN: Trakt watchlist/" + type + " returned HTTP "
                                   + resp.statusCode() + ", skipping.");
                break;
            }
            String pageCount = resp.header("X-Pagination-Page-Count");
            if (pageCount != null) totalPages = Integer.parseInt(pageCount);

            for (JsonNode row : resp.json()) {
                Integer tmdbId = extractTmdbId(row, type);
                if (tmdbId == null) continue;
                String title = extractTitle(row, type);
                items.add(new MediaItem(mediaType, tmdbId, title));
            }
            page++;
        }
        return items;
    }

    private Integer extractTmdbId(JsonNode row, String type) {
        String key = "movies".equals(type) ? "movie" : "show";
        JsonNode media = row.get(key);
        if (media == null) return null;
        JsonNode ids = media.get("ids");
        if (ids == null || ids.get("tmdb") == null || ids.get("tmdb").isNull()) return null;
        return ids.get("tmdb").asInt();
    }

    private String extractTitle(JsonNode row, String type) {
        String key = "movies".equals(type) ? "movie" : "show";
        JsonNode media = row.get(key);
        if (media == null) return "Unknown";
        JsonNode title = media.get("title");
        return title != null ? title.asText("Unknown") : "Unknown";
    }

    // -------------------------------------------------------------------------
    // Shared headers
    // -------------------------------------------------------------------------

    private Map<String, String> traktHeaders() {
        return Map.of(
            "Content-Type",       "application/json",
            "trakt-api-version",  "2",
            "trakt-api-key",      cfg.traktClientId()
        );
    }
}
