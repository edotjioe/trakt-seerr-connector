package dev.traktseerr.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

/**
 * Thin wrapper around java.net.http.HttpClient that handles JSON parsing
 * and an optional trust-all SSL context (for Seerr instances with self-signed certs).
 */
public class HttpHelper {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;
    private final Duration   timeout;

    public HttpHelper(int timeoutSeconds, boolean verifySsl) {
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NORMAL);
        if (!verifySsl) {
            try {
                SSLContext ctx = buildTrustAllContext();
                builder.sslContext(ctx);
            } catch (Exception e) {
                throw new RuntimeException("Failed to build trust-all SSL context", e);
            }
        }
        this.client = builder.build();
    }

    // -------------------------------------------------------------------------
    // Request / Response
    // -------------------------------------------------------------------------

    public record Response(int statusCode, String body, java.net.http.HttpHeaders headers) {
        public JsonNode json() {
            try {
                return MAPPER.readTree(body);
            } catch (Exception e) {
                throw new RuntimeException("Non-JSON response (HTTP " + statusCode + "): " + body, e);
            }
        }
        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }
    }

    public Response get(String url, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .GET()
            .timeout(timeout);
        headers.forEach(req::header);
        HttpResponse<String> r = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body(), r.headers());
    }

    public Response post(String url, String jsonBody, Map<String, String> headers) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept",       "application/json");
        headers.forEach(req::header);
        HttpResponse<String> r = client.send(req.build(), HttpResponse.BodyHandlers.ofString());
        return new Response(r.statusCode(), r.body(), r.headers());
    }

    // -------------------------------------------------------------------------
    // Trust-all SSL context (only used when seerrVerifySsl=false)
    // -------------------------------------------------------------------------

    private static SSLContext buildTrustAllContext() throws Exception {
        TrustManager[] tm = {
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
            }
        };
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tm, new java.security.SecureRandom());
        return ctx;
    }
}
