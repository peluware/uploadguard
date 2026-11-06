package com.peluware.uploadguard;

/**
 * Configuration, read once from the environment at startup.
 * <p>
 * There is no list of accepted media types here on purpose. Which types may be sent at all is a
 * decision for whatever sits in front of this proxy or behind it; all this service does is check that
 * a body is what its header claims. See {@link ContentInspector}.
 *
 * @param upstream base URL of the backend, guaranteed without a trailing slash
 * @param port     port to listen on
 */
public record Settings(String upstream, int port) {

    public static final int DEFAULT_PORT = 8080;

    public static Settings fromEnv() {
        String upstream = System.getenv("UPSTREAM_URL");
        if (upstream == null || upstream.isBlank()) {
            throw new IllegalStateException("UPSTREAM_URL environment variable is required");
        }
        // A trailing slash would double up with the raw path of every request
        while (upstream.endsWith("/")) {
            upstream = upstream.substring(0, upstream.length() - 1);
        }

        String rawPort = System.getenv().getOrDefault("PORT", String.valueOf(DEFAULT_PORT));
        int port;
        try {
            port = Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("PORT is not a number: " + rawPort);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("PORT out of range: " + port);
        }

        return new Settings(upstream, port);
    }
}
