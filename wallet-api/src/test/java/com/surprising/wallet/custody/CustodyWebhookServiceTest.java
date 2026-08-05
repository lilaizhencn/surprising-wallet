package com.surprising.wallet.custody;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.service.CustodyCryptoService;
import com.surprising.wallet.service.CustodyWebhookService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CustodyWebhookServiceTest {

    @Test
    void signsExactlyTheUtf8BytesReceivedByTheWebhookEndpoint() throws Exception {
        AtomicReference<byte[]> receivedBody = new AtomicReference<>();
        AtomicReference<String> receivedTimestamp = new AtomicReference<>();
        AtomicReference<String> receivedSignature = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> respond(exchange, receivedBody, receivedTimestamp,
                receivedSignature));
        server.start();
        try {
            CustodyWebhookService service = new CustodyWebhookService(null,
                    new CustodyCryptoService(properties()), new ObjectMapper(), "dev");
            UUID eventId = UUID.randomUUID();
            String eventType = "WITHDRAWAL.CREATED";
            String body = "{\"message\":\"中文\", \"amount\":\"1.2300\"}";

            CustodyWebhookService.WebhookHttpResult result = service.send(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook",
                    "webhook-secret", eventId, eventType, body);

            assertEquals(200, result.statusCode());
            assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), receivedBody.get());
            String expected = sign("webhook-secret", receivedTimestamp.get(), eventId, eventType,
                    receivedBody.get());
            assertEquals("v1=" + expected, receivedSignature.get());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, AtomicReference<byte[]> body,
                                AtomicReference<String> timestamp, AtomicReference<String> signature)
            throws IOException {
        try (exchange) {
            body.set(exchange.getRequestBody().readAllBytes());
            timestamp.set(exchange.getRequestHeaders().getFirst("X-Custody-Timestamp"));
            signature.set(exchange.getRequestHeaders().getFirst("X-Custody-Signature"));
            byte[] response = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
        }
    }

    private static String sign(String secret, String timestamp, UUID eventId, String eventType, byte[] body)
            throws Exception {
        byte[] prefix = (timestamp + "." + eventId + "." + eventType + ".")
                .getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + body.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(body, 0, message, prefix.length, body.length);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(message));
    }

    private static CustodySecurityProperties properties() {
        CustodySecurityProperties properties = new CustodySecurityProperties();
        properties.setSecretMasterKey(Base64.getEncoder().encodeToString(new byte[32]));
        return properties;
    }
}
