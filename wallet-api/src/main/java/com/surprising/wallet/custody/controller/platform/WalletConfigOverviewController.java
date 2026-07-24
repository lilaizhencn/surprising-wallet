package com.surprising.wallet.custody.controller.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.service.WalletConfigOverviewService;

/**
 * 平台钱包配置概览控制器。
 *
 * <p>端点路径：/custody/platform/v1/wallet-config/overview。
 * 提供平台统计信息、全局开关状态、链配置摘要等总览数据。
 */
@RestController
@RequestMapping("/custody/platform/v1/wallet-config")
public class WalletConfigOverviewController {
    /** 配置总览服务，返回平台统计与全局开关。 */
    private final WalletConfigOverviewService service;

    /**
     * 注入总览服务。
     */
    public WalletConfigOverviewController(WalletConfigOverviewService service) {
        this.service = service;
    }

    /**
     * 获取平台配置摘要（开关、状态、计数）。
     */
    @GetMapping("/summary")
    public WalletConfigOverviewService.SummaryView summary(HttpServletRequest request) {
        return service.summary(CustodyRequestSupport.requirePrincipal(request));
    }

    /**
     * 更新全局功能开关。
     */
    @PatchMapping("/global-switches")
    public WalletConfigOverviewService.SummaryView updateGlobalSwitches(
            @RequestBody WalletConfigOverviewService.UpdateGlobalSwitchesCommand body,
            HttpServletRequest request) {
        return service.updateGlobalSwitches(
                CustodyRequestSupport.requirePrincipal(request),
                body,
                CustodyRequestSupport.clientIp(request));
    }
}
