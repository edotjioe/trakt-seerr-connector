package dev.traktseerr.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.traktseerr.config.Settings;

import java.util.HashMap;
import java.util.Map;

/**
 * Seerr (Overseerr / Jellyseerr) API client.
 * Mirrors seerr_client.py.
 */
public class SeerrClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static class SeerrRequestException extends RuntimeException {
        public final int statusCode;
        public final String responseBody;
        public SeerrRequestException(int statusCode, String message, String body) {
            super(message);
            this.statusCode   = statusCode;
            this.responseBody = body;
        }
    }

    private final Settings   cfg;
    private final HttpHelper http;

    public SeerrClient(Settings cfg) {
        this.cfg  = cfg;
        this.http = new HttpHelper(cfg.httpTimeoutSeconds(), cfg.seerrVerifySsl());
    }

    /**
     * POST /api/v1/request and return (statusCode, responseBody).
     * Throws SeerrRequestException for HTTP >= 400.
     */
    public record SeerrResult(int statusCode, String body) {}

    public SeerrResult requestMedia(String mediaType, int tmdbId) throws Exception {
        String url  = cfg.seerrBaseUrl().stripTrailing() + "/api/v1/request";
        String body = MAPPER.writeValueAsString(buildRequestBody(mediaType, tmdbId));
        Map<String, String> hdrs = buildHeaders();

        HttpHelper.Response resp = http.post(url, body, hdrs);
        if (resp.statusCode() >= 400) {
            throw new SeerrRequestException(
                resp.statusCode(),
                "Seerr returned HTTP " + resp.statusCode(),
                resp.body()
            );
        }
        return new SeerrResult(resp.statusCode(), resp.body());
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private ObjectNode buildRequestBody(String mediaType, int tmdbId) {
        ObjectNode node = MAPPER.createObjectNode()
            .put("mediaType", mediaType)
            .put("mediaId",   tmdbId);

        if ("tv".equals(mediaType)) {
            if ("all".equals(cfg.tvSeasonsMode())) {
                node.put("seasons", "all");
            } else {
                ArrayNode seasons = node.putArray("seasons");
                seasons.add(1);
            }
        }
        return node;
    }

    private Map<String, String> buildHeaders() {
        Map<String, String> hdrs = new HashMap<>();
        hdrs.put("X-Api-Key", cfg.seerrApiKey());
        hdrs.put("Accept",    "application/json");
        if (cfg.seerrApiUserId() != null && !cfg.seerrApiUserId().isBlank()) {
            hdrs.put("X-Api-User", cfg.seerrApiUserId());
        }
        return hdrs;
    }
}
