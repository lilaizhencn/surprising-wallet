package com.surprising.wallet.custody.controller.console;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.surprising.wallet.custody.service.CustodyAssetDashboardService;
import com.surprising.wallet.custody.model.CustodyRequestSupport;

/**
 * Console 仪表盘控制器。
 *
 * <p>端点路径：/custody/console/v1/{tenantId}/dashboard。
 * 提供租户级资产总览：总余额、各链分布、今日充值/提现统计。
 */
@RestController
@RequestMapping("/custody/console/v1/dashboard")
public class CustodyConsoleDashboardController {
    /** 总览服务：资产、交易、告警等面板指标。 */
    private final CustodyAssetDashboardService dashboard;

    /**
     * 注入仪表盘服务。
     */
    public CustodyConsoleDashboardController(CustodyAssetDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    /**
     * 查询当前租户控制台首页统计信息。
     */
    @GetMapping
    public CustodyAssetDashboardService.Dashboard dashboard(HttpServletRequest request) {
        return dashboard.dashboard(CustodyRequestSupport.requirePrincipal(request));
    }
}
