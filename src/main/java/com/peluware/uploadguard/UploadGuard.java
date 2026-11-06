package com.peluware.uploadguard;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import static java.lang.System.Logger.Level.ERROR;
import static java.lang.System.Logger.Level.INFO;

/**
 * Reverse proxy that refuses a request whose body contradicts its {@code Content-Type}, and relays
 * everything else untouched.
 * <p>
 * Run with {@code -Djdk.httpclient.allowRestrictedHeaders=host}, without which the HttpClient refuses
 * to send the client's {@code Host} header.
 */
public final class UploadGuard {

    private static final System.Logger LOG = System.getLogger(UploadGuard.class.getName());

    private UploadGuard() {
    }

    public static void main(String[] args) throws IOException {
        Settings settings;
        try {
            settings = Settings.fromEnv();
        } catch (IllegalStateException e) {
            LOG.log(ERROR, e.getMessage());
            System.exit(1);
            return;
        }

        var handler = new UploadHandler(new ContentInspector(), new Upstream(settings.upstream()));

        HttpServer server;
        try {
            server = HttpServer.create(new InetSocketAddress(settings.port()), 0);
        } catch (BindException e) {
            LOG.log(ERROR, "port {0} is already in use", String.valueOf(settings.port()));
            System.exit(1);
            return;
        }

        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", handler);
        server.start();

        LOG.log(INFO, "UploadGuard listening on port {0}, forwarding to {1}", String.valueOf(settings.port()), settings.upstream());
    }
}
