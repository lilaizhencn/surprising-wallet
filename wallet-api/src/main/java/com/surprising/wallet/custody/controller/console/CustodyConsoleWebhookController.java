package com.surprising.wallet.custody.controller.console;

import com.surprising.wallet.custody.service.CustodyWebhookService.CreateWebhookCommand;
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

import com.surprising.wallet.custody.repository.CustodyRepository;
import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.service.CustodyWebhookService;

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleWebhookController {
    /** Webhook 服务：管理回调地址、投递与重试。 */
    private final CustodyWebhookService webhooks;

    /**
     * 注入 webhook 服务。
     */
    public CustodyConsoleWebhookController(CustodyWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /**
     * 查询当前租户 webhook 端点与状态列表。
     */
    @GetMapping("/webhooks")
    public List<Map<String, Object>> webhooks(HttpServletRequest request) {
        return webhooks.list(CustodyRequestSupport.requirePrincipal(request));
    }

    /**
     * 创建 webhook 端点，写入回调目标与签名参数。
     */
    @PostMapping("/webhooks")
    public CustodyWebhookService.CreatedWebhook create(@RequestBody CreateWebhookCommand body,
                                                       HttpServletRequest request) {
        return webhooks.create(CustodyRequestSupport.requirePrincipal(request), body,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 手工触发单条 webhook 地址校验。
     */
    @PostMapping("/webhooks/{endpointId}/verify")
    public CustodyRepository.WebhookEndpointRecord verify(
            @PathVariable UUID endpointId, HttpServletRequest request) {
        return webhooks.verify(CustodyRequestSupport.requirePrincipal(request), endpointId,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 启停 webhook 投递开关。
     */
    @PatchMapping("/webhooks/{endpointId}/status")
    public Map<String, Object> status(@PathVariable UUID endpointId,
                                      @RequestBody WebhookStatusRequest body,
                                      HttpServletRequest request) {
        webhooks.setEnabled(CustodyRequestSupport.requirePrincipal(request), endpointId,
                body.enabled(), CustodyRequestSupport.clientIp(request));
        return Map.of("ok", true);
    }

    /**
     * 分页查询投递记录，支持状态与 endpoint 过滤。
     */
    @GetMapping("/webhook-deliveries")
    public List<Map<String, Object>> deliveries(
            @RequestParam(required = false) UUID endpointId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return webhooks.deliveries(
                CustodyRequestSupport.requirePrincipal(request), endpointId, status, limit, offset);
    }

    /**
     * 重试失败投递，返回当前任务是否重新入队。
     */
    @PostMapping("/webhook-deliveries/{deliveryId}/retry")
    public Map<String, Object> retry(@PathVariable UUID deliveryId, HttpServletRequest request) {
        webhooks.retry(CustodyRequestSupport.requirePrincipal(request), deliveryId,
                CustodyRequestSupport.clientIp(request));
        return Map.of("ok", true);
    }

    @PostMapping("/webhook-deliveries/retry-failed")
    public Map<String, Object> retryFailed(@RequestParam UUID endpointId,
                                           HttpServletRequest request) {
        int queued = webhooks.retryFailed(
                CustodyRequestSupport.requirePrincipal(request), endpointId,
                CustodyRequestSupport.clientIp(request));
        return Map.of("queued", queued);
    }

    /**
     * 查询单次 webhook 投递尝试历史，支持分页。
     */
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

    /**
     * webhook 状态变更请求体。
     */
    public record WebhookStatusRequest(boolean enabled) {
    }
}
