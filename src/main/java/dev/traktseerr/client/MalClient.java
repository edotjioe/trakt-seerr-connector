package dev.traktseerr.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.traktseerr.config.Settings;
import dev.traktseerr.model.MediaItem;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyAnimeList sync via the Jikan public API + TMDB title search.
 * Mirrors mal_client.py.
 */
public class MalClient {

    private static final String JIKAN_BASE = "https://api.jikan.moe/v4";
    private static final String TMDB_BASE  = "https://api.themoviedb.org/3";
    private static final long   JIKAN_DELAY_MS = 350; // ~2.8 req/s — respects Jikan rate limit

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern YEAR_PATTERN = Pattern.compile("\\b(\\d{4})\\b");

    private final Settings   cfg;
    private final HttpHelper http;

    public MalClient(Settings cfg) {
        this.cfg  = cfg;
        this.http = new HttpHelper(cfg.httpTimeoutSeconds(), true);
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Iterate all configured MAL list statuses and map each anime to a TMDB MediaItem.
     * Items that cannot be mapped to TMDB are silently skipped with a warning log.
     */
    public List<MediaItem> fetchAnimeList() throws Exception {
        List<MediaItem> items = new ArrayList<>();
        for (String status : cfg.malListStatuses()) {
            items.addAll(fetchByStatus(status));
        }
        return items;
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private List<MediaItem> fetchByStatus(String status) throws Exception {
        List<MediaItem> items = new ArrayList<>();
        int page = 1;
        boolean hasNextPage = true;

        while (hasNextPage) {
            String url = JIKAN_BASE + "/users/" + encode(cfg.malUsername())
                         + "/animelist?status=" + encode(status)
                         + "&page=" + page;
            HttpHelper.Response resp = http.get(url, Map.of());

            if (resp.statusCode() == 404) {
                System.err.println("WARN: MAL user '" + cfg.malUsername() + "' not found.");
                return items;
            }
            if (resp.statusCode() == 429) {
                System.err.println("WARN: Jikan rate limit hit, sleeping 5 s…");
                Thread.sleep(5000);
                continue;
            }
            if (resp.statusCode() != 200) {
                System.err.println("WARN: Jikan returned HTTP " + resp.statusCode()
                                   + " for status=" + status + ", skipping.");
                break;
            }

            JsonNode json = resp.json();
            JsonNode data = json.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) break;

            for (JsonNode entry : data) {
                Thread.sleep(JIKAN_DELAY_MS);
                MediaItem item = entryToTmdb(entry);
                if (item != null) items.add(item);
            }

            JsonNode pagination = json.get("pagination");
            hasNextPage = pagination != null
                          && pagination.has("has_next_page")
                          && pagination.get("has_next_page").asBoolean(false);
            page++;
        }
        return items;
    }

    private MediaItem entryToTmdb(JsonNode entry) throws Exception {
        JsonNode animeNode = entry.get("node");
        if (animeNode == null) return null;
        int malId = animeNode.get("id").asInt();

        // Fetch full anime details to get type, english title, year
        JsonNode details = fetchAnimeDetails(malId);
        if (details == null) return null;

        String jikanType = details.has("type") ? details.get("type").asText("") : "";
        String mediaType = jikanTypeToMediaType(jikanType);

        // Prefer English title, fall back to canonical title
        String title = null;
        JsonNode titles = details.get("titles");
        if (titles != null && titles.isArray()) {
            for (JsonNode t : titles) {
                if ("English".equals(t.path("type").asText())) {
                    title = t.path("title").asText();
                    break;
                }
            }
        }
        if (title == null || title.isBlank()) {
            title = details.has("title_english") && !details.get("title_english").isNull()
                    ? details.get("title_english").asText()
                    : details.path("title").asText("");
        }
        if (title.isBlank()) return null;

        Integer year = extractYear(details);
        Integer tmdbId = searchTmdbId(mediaType, title, year);
        if (tmdbId == null) {
            System.err.println("WARN: Could not map '" + title + "' (MAL " + malId + ") to TMDB, skipping.");
            return null;
        }
        return new MediaItem(mediaType, tmdbId, title);
    }

    private JsonNode fetchAnimeDetails(int malId) throws Exception {
        String url = JIKAN_BASE + "/anime/" + malId;
        HttpHelper.Response resp = http.get(url, Map.of());
        if (resp.statusCode() == 429) {
            Thread.sleep(5000);
            resp = http.get(url, Map.of());
        }
        if (resp.statusCode() != 200) return null;
        JsonNode json = resp.json();
        return json.get("data");
    }

    /** "Movie" → "movie", anything else → "tv" */
    private String jikanTypeToMediaType(String jikanType) {
        return "Movie".equalsIgnoreCase(jikanType) ? "movie" : "tv";
    }

    private Integer extractYear(JsonNode anime) {
        if (anime.has("year") && !anime.get("year").isNull()) {
            int y = anime.get("year").asInt(0);
            if (y > 0) return y;
        }
        // Try aired.prop.from.year
        try {
            int y = anime.path("aired").path("prop").path("from").path("year").asInt(0);
            if (y > 0) return y;
        } catch (Exception ignored) {}
        // Try regex on aired.string
        String airedStr = anime.path("aired").path("string").asText("");
        Matcher m = YEAR_PATTERN.matcher(airedStr);
        if (m.find()) return Integer.parseInt(m.group(1));
        return null;
    }

    private Integer searchTmdbId(String mediaType, String title, Integer year) throws Exception {
        String endpoint = "movie".equals(mediaType) ? "/search/movie" : "/search/tv";
        String yearParam = "movie".equals(mediaType) ? "year" : "first_air_date_year";
        String url = TMDB_BASE + endpoint
                     + "?api_key=" + encode(cfg.tmdbApiKey())
                     + "&query=" + encode(title)
                     + "&include_adult=false"
                     + (year != null ? "&" + yearParam + "=" + year : "");

        HttpHelper.Response resp = http.get(url, Map.of());
        if (resp.statusCode() == 429) {
            Thread.sleep(5000);
            resp = http.get(url, Map.of());
        }
        if (resp.statusCode() != 200) return null;

        JsonNode results = resp.json().get("results");
        if (results == null || !results.isArray() || results.isEmpty()) return null;
        JsonNode first = results.get(0);
        return first.has("id") ? first.get("id").asInt() : null;
    }

    private static String encode(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }
}
