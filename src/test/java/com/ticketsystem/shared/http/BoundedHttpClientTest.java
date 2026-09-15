package com.ticketsystem.shared.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BoundedHttpClientTest {

    @Test
    @DisplayName("a slow HTTP dependency times out after one attempt")
    void requestDeadlineDoesNotBlindRetry() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ExecutorService serverExecutor = Executors.newSingleThreadExecutor();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.setExecutor(serverExecutor);
        server.createContext("/slow", exchange -> {
            attempts.incrementAndGet();
            try {
                Thread.sleep(Duration.ofSeconds(1));
                byte[] body = "late".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();

        try {
            BoundedHttpClient client = new BoundedHttpClient(
                    new OutboundHttpProperties(Duration.ofMillis(100), Duration.ofMillis(150)));
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(
                    "http://127.0.0.1:%d/slow".formatted(server.getAddress().getPort()))).GET();

            long started = System.nanoTime();
            assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString()))
                    .isInstanceOf(HttpTimeoutException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertThat(elapsed).isLessThan(Duration.ofSeconds(1));
            assertThat(attempts).hasValue(1);
        } finally {
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }
}
