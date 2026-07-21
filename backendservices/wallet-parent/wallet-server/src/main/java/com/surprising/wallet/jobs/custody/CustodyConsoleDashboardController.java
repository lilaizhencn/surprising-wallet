package com.surprising.wallet.jobs.custody;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/custody/console/v1/dashboard")
public class CustodyConsoleDashboardController {
    private final CustodyAssetDashboardService dashboard;

    public CustodyConsoleDashboardController(CustodyAssetDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    public CustodyAssetDashboardService.Dashboard dashboard(HttpServletRequest request) {
        return dashboard.dashboard(CustodyRequestSupport.requirePrincipal(request));
    }
}
