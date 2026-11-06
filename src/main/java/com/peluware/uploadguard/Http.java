package com.peluware.uploadguard;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Small helpers shared by the handler and the relay. */
final class Http {

    private Http() {
    }

    /** Answers with a status and a one-line plain-text explanation. */
    static void reply(HttpExchange ex, int status, String message) throws IOException {
        byte[] body = (message + "\n").getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");

        // This answer is generated here, so the backend's CORS headers never reach the client and a
        // browser is not allowed to read the status: the caller sees a network error instead of the
        // reason. Reflecting the origin exposes nothing but this one line, to someone who had to be
        // authorized to get here at all.
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank()) {
            ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
            ex.getResponseHeaders().set("Vary", "Origin");
        }

        ex.sendResponseHeaders(status, body.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /**
     * Discards what is left of a rejected body, up to {@code max} bytes, so the client gets to read the
     * response: an HTTP/1.1 server that answers mid-request has to close the connection, and many
     * clients report that reset instead of the status code. Past the limit the connection is simply
     * dropped, rather than reading a huge body only to throw it away.
     */
    static void drain(InputStream in, int max) {
        try {
            byte[] buf = new byte[8192];
            int total = 0;
            while (total < max) {
                int n = in.read(buf);
                if (n < 0) break;
                total += n;
            }
        } catch (IOException e) {
            // the client gave up first, nothing to do
        }
    }

    /** {@code "image/png; charset=x"} to {@code "image/png"}. */
    static String baseType(String contentType) {
        if (contentType == null || contentType.isBlank()) return null;
        return contentType.split(";")[0].trim().toLowerCase(Locale.ROOT);
    }
}
