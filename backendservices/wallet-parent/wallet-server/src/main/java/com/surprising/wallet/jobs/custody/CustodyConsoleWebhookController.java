package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.jobs.custody.CustodyWebhookService.CreateWebhookCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleWebhookController {
    private final CustodyWebhookService webhooks;

    public CustodyConsoleWebhookController(CustodyWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    @GetMapping("/webhooks")
    public List<Map<String, Object>> webhooks(HttpServletRequest request) {
        return webhooks.list(CustodyRequestSupport.requirePrincipal(request));
    }

    @PostMapping("/webhooks")
    public CustodyWebhookService.CreatedWebhook create(@RequestBody CreateWebhookCommand body,
                                                       HttpServletRequest request) {
        return webhooks.create(CustodyRequestSupport.requirePrincipal(request), body,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/webhooks/{endpointId}/verify")
    public CustodyRepository.WebhookEndpointRecord verify(
            @PathVariable UUID endpointId, HttpServletRequest request) {
        return webhooks.verify(CustodyRequestSupport.requirePrincipal(request), endpointId,
                CustodyRequestSupport.clientIp(request));
    }

    @PatchMapping("/webhooks/{endpointId}/status")
    public Map<String, Object> status(@PathVariable UUID endpointId,
                                      @RequestBody WebhookStatusRequest body,
                                      HttpServletRequest request) {
        webhooks.setEnabled(CustodyRequestSupport.requirePrincipal(request), endpointId,
                body.enabled(), CustodyRequestSupport.clientIp(request));
        return Map.of("ok", true);
    }

    @GetMapping("/webhook-deliveries")
    public List<Map<String, Object>> deliveries(
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return webhooks.deliveries(
                CustodyRequestSupport.requirePrincipal(request), endpointId, limit, offset);
    }

    @PostMapping("/webhook-deliveries/{deliveryId}/retry")
    public Map<String, Object> retry(@PathVariable UUID deliveryId, HttpServletRequest request) {
        webhooks.retry(CustodyRequestSupport.requirePrincipal(request), deliveryId,
                CustodyRequestSupport.clientIp(request));
        return Map.of("ok", true);
    }

    @GetMapping("/webhook-deliveries/{deliveryId}/attempts")
    public List<Map<String, Object>> attempts(
            @PathVariable UUID deliveryId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return webhooks.deliveryAttempts(
                CustodyRequestSupport.requirePrincipal(request),
                deliveryId,
                limit,
                offset);
    }

    public record WebhookStatusRequest(boolean enabled) {
    }
}
