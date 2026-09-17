package com.ticketsystem.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.ticketsystem.schema.AbstractPostgresIT;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Value;

/** Real servlet-container check catches filter registration/order problems that MockMvc can miss. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthHttpIT extends AbstractPostgresIT {
    @Value("${local.server.port}") private int port;

    @Test
    void actualHttpLoginBookingAndLogoutUseTheCustomFilter() throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<String> login = client.send(request("/api/v1/auth/login")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"test-user\",\"password\":\"test-user-password\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            String token = JsonPath.read(login.body(), "$.accessToken");
            String refresh = cookie(login, "__Host-ticket_refresh");
            String csrf = cookie(login, "__Host-ticket_csrf");

            HttpResponse<String> booked = client.send(request("/api/v1/events/" + HOT_EVENT + "/reservations")
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString("{\"quantity\":1}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(booked.statusCode()).isEqualTo(201);
            assertThat(JsonPath.<String>read(booked.body(), "$.userId")).isEqualTo("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

            HttpResponse<String> logout = client.send(request("/api/v1/auth/logout")
                    .header("Cookie", "__Host-ticket_refresh=" + refresh + "; __Host-ticket_csrf=" + csrf)
                    .header("X-CSRF-TOKEN", csrf).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(logout.statusCode()).isEqualTo(204);

            HttpResponse<String> rejected = client.send(request("/api/v1/auth/me")
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(rejected.statusCode()).isEqualTo(401);
            assertThat(JsonPath.<String>read(rejected.body(), "$.code")).isEqualTo("UNAUTHORIZED");
        }
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(10));
    }

    private static String cookie(HttpResponse<?> response, String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(value -> value.startsWith(name + "="))
                .map(value -> value.substring(name.length() + 1, value.indexOf(';'))).findFirst().orElseThrow();
    }
}
