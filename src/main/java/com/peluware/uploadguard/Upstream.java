package com.peluware.uploadguard;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;

import static java.lang.System.Logger.Level.WARNING;

/** Relays an exchange to the backend and copies the answer back, changing as little as possible. */
public final class Upstream {

    private static final System.Logger LOG = System.getLogger(Upstream.class.getName());

    /** Managed by the HttpClient, or about the connection rather than the message. */
    private static final Set<String> SKIP_HEADERS = Set.of(
        "host",
        "content-length",
        "connection",
        "expect",
        "upgrade",
        "transfer-encoding",
        "keep-alive",
        "te",
        "trailer",
        "proxy-connection"
    );

    /** Same, plus what the HttpServer writes by itself. */
    private static final Set<String> SKIP_RESPONSE_HEADERS = Set.of("date");

    /** {@code sendResponseHeaders}: nobody at all. */
    private static final long NO_BODY = -1;

    /** {@code sendResponseHeaders}: a body whose length is not known, sent chunked. */
    private static final long CHUNKED = 0;

    private final String baseUrl;

    /** No {@code HttpRequest.timeout}: it bounds the whole exchange and would cut off large bodies. */
    private final HttpClient client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER) // a proxy hands the 3xx back, it does not follow it
            .build();

    public Upstream(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void relay(HttpExchange ex, BodyPublisher body) throws IOException, InterruptedException {
        String host = ex.getRequestHeaders().getFirst("Host");
        if (host == null || host.isBlank()) {
            LOG.log(WARNING, "No Host header on {0} {1} over {2}", ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getProtocol());
            Http.reply(ex, 400, "Host header is required");
            return;
        }

        // Raw path and query: decoding or normalizing them would change the request
        URI in = ex.getRequestURI();
        String target = in.getRawPath() + (in.getRawQuery() != null ? "?" + in.getRawQuery() : "");

        var builder = HttpRequest.newBuilder(URI.create(baseUrl + target))
                .method(ex.getRequestMethod(), body)
                .header("Host", host);

        ex.getRequestHeaders().forEach((name, values) -> {
            if (!SKIP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                values.forEach(v -> builder.header(name, v));
            }
        });

        // Streamed: reading it into an array would be an unbounded allocation decided by the other end
        HttpResponse<InputStream> resp = client.send(builder.build(), BodyHandlers.ofInputStream());

        resp.headers().map().forEach((name, values) -> {
            String key = name.toLowerCase(Locale.ROOT);
            if (!SKIP_HEADERS.contains(key) && !SKIP_RESPONSE_HEADERS.contains(key)) {
                ex.getResponseHeaders().put(name, new ArrayList<>(values));
            }
        });

        long length = responseLength(resp, ex.getRequestMethod());
        ex.sendResponseHeaders(resp.statusCode(), length);
        try (InputStream upstreamBody = resp.body()) {
            if (length != NO_BODY) {
                try (OutputStream out = ex.getResponseBody()) {
                    upstreamBody.transferTo(out);
                }
            }
        }
    }

    private static long responseLength(HttpResponse<InputStream> resp, String requestMethod) {
        // A HEAD answer declares the length of a body it does not send
        if ("HEAD".equalsIgnoreCase(requestMethod)) return NO_BODY;

        int status = resp.statusCode();
        if (status == 204 || status == 304) return NO_BODY;

        long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
        if (declared == 0) return NO_BODY;
        return declared > 0 ? declared : CHUNKED;
    }
}
