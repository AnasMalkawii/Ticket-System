package com.ticketsystem.shared.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.springframework.stereotype.Component;

/**
 * Minimal outbound HTTP boundary with connect and whole-request deadlines.
 *
 * <p>There is intentionally no automatic retry here. A feature adapter may retry only when
 * its operation has an explicit idempotency contract; purchase/payment calls do not.
 */
@Component
public class BoundedHttpClient {

    private final HttpClient client;
    private final OutboundHttpProperties properties;

    public BoundedHttpClient(OutboundHttpProperties properties) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.properties = properties;
    }

    public <T> HttpResponse<T> send(
            HttpRequest.Builder request, HttpResponse.BodyHandler<T> bodyHandler)
            throws IOException, InterruptedException {
        return client.send(request.timeout(properties.requestTimeout()).build(), bodyHandler);
    }
}
