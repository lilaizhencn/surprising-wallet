package com.surprising.wallet.custody.controller.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.service.WalletConfigOverviewService;

@RestController
@RequestMapping("/custody/platform/v1/wallet-config")
public class WalletConfigOverviewController {
    private final WalletConfigOverviewService service;

    public WalletConfigOverviewController(WalletConfigOverviewService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public WalletConfigOverviewService.SummaryView summary(HttpServletRequest request) {
        return service.summary(CustodyRequestSupport.requirePrincipal(request));
    }

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
