package com.surprising.wallet.gateway;

import com.surprising.wallet.service.CustodyCryptoService;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/** Webhook URL 策略、原始 UTF-8 请求签名与有界 HTTP 响应。 */
@Component
public class CustodyWebhookHttpClient implements AutoCloseable {
    private final CustodyCryptoService crypto;
    private final HttpClient httpClient;
    private final boolean production;

    public CustodyWebhookHttpClient(CustodyCryptoService crypto,
                                    @Value("${sw.app.env.name:dev}") String environment) {
        this.crypto = crypto;
        this.production = "prod".equalsIgnoreCase(environment) || "production".equalsIgnoreCase(environment);
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public WebhookHttpResult send(String url, String secret, UUID eventId, String eventType, String body) {
        URI uri = validateEndpoint(url);
        long timestamp = Instant.now().getEpochSecond();
        byte[] prefix = (timestamp + "." + eventId + "." + eventType + ".").getBytes(StandardCharsets.UTF_8);
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefix.length + bodyBytes.length];
        System.arraycopy(prefix, 0, message, 0, prefix.length);
        System.arraycopy(bodyBytes, 0, message, prefix.length, bodyBytes.length);
        String signature = crypto.hmacSha256(secret, message);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("User-Agent", "Surprising-Wallet-Webhook/1.0")
                .header("X-Custody-Event-Id", eventId.toString())
                .header("X-Custody-Event-Type", eventType)
                .header("X-Custody-Timestamp", Long.toString(timestamp))
                .header("X-Custody-Signature", "v1=" + signature)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(4097);
                return new WebhookHttpResult(response.statusCode(),
                        new String(bytes, 0, Math.min(bytes.length, 4096), StandardCharsets.UTF_8),
                        response.headers().firstValue("Retry-After").orElse(null));
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("webhook request was interrupted", error);
        } catch (IOException error) {
            throw new IllegalStateException("webhook request failed: " + error.getMessage(), error);
        }
    }

    public URI validateEndpoint(String value) {
        String url = value == null ? "" : value.trim();
        if (url.isBlank() || url.length() > 2048) throw new IllegalArgumentException("valid webhook URL is required");
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!"https".equals(scheme) && !(!production && "http".equals(scheme))) {
                throw new IllegalArgumentException("webhook URL must use HTTPS");
            }
            if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException("webhook URL must not contain credentials or a fragment");
            }
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                boolean local = address.isAnyLocalAddress() || address.isLoopbackAddress()
                        || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress();
                if (local && !(!production && address.isLoopbackAddress())) {
                    throw new IllegalArgumentException("webhook URL must not resolve to a private or link-local address");
                }
            }
            return uri;
        } catch (URISyntaxException | IOException error) {
            throw new IllegalArgumentException("webhook URL is invalid or cannot be resolved", error);
        }
    }

    @PreDestroy
    @Override
    public void close() { httpClient.close(); }

    public record WebhookHttpResult(int statusCode, String body, String retryAfter) { }
}
