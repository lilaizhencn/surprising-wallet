package com.surprising.wallet.custody;

import com.surprising.wallet.controller.CustodyErrorController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustodyErrorControllerTest {
    @Test
    void servletErrorsReturnSafeJsonEvenWhenHtmlIsRequested() throws Exception {
        // Real Tomcat error dispatch is required; a controller unit test misses the view loop.
        try (var context = (ServletWebServerApplicationContext) SpringApplication.run(TestApplication.class,
                "--spring.config.location=optional:classpath:/error-controller-test-none.yaml",
                "--server.address=127.0.0.1", "--server.port=0",
                "--spring.web.error.whitelabel.enabled=false",
                "--spring.autoconfigure.exclude=org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")) {
            try (var client = HttpClient.newHttpClient()) {
                String base = "http://127.0.0.1:" + context.getWebServer().getPort();
                for (String accept : new String[]{"text/html", "application/json", "*/*"}) {
                    assertError(client, base, "/index.php", accept, 404, "NOT_FOUND");
                    assertError(client, base, "/test-failure", accept, 500, "INTERNAL_SERVER_ERROR");
                    assertError(client, base, "/error", accept, 500, "INTERNAL_SERVER_ERROR");
                }
                var request = HttpRequest.newBuilder(URI.create(base + "/test-failure"))
                        .POST(HttpRequest.BodyPublishers.noBody()).header("Accept", "text/html").build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertEquals(405, response.statusCode());
                assertTrue(response.body().contains("METHOD_NOT_ALLOWED"));
            }
        }
    }

    private static void assertError(HttpClient client, String base, String path, String accept,
                                    int status, String code) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path)).header("Accept", accept).build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode());
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        assertTrue(response.body().contains("\"code\":\"" + code + "\""));
        assertFalse(response.body().contains("sensitive-internal-detail"));
        assertFalse(response.body().contains("Circular view path"));
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({CustodyErrorController.class, FailingController.class})
    static class TestApplication { }

    @RestController
    static class FailingController {
        @GetMapping("/test-failure")
        String fail() {
            throw new RuntimeException("sensitive-internal-detail");
        }
    }
}
