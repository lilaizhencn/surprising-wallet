package com.surprising.wallet.jobs.custody;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/custody/platform/v1/wallet-config/keyset")
public class WalletKeyConfigController {
    private final WalletKeyConfigService service;

    public WalletKeyConfigController(WalletKeyConfigService service) {
        this.service = service;
    }

    @GetMapping
    public WalletKeyConfigService.KeysetView get(HttpServletRequest request) {
        return service.get(CustodyRequestSupport.requirePrincipal(request));
    }

    @PutMapping
    public WalletKeyConfigService.KeysetView save(
            @RequestBody WalletKeyConfigService.SaveKeysetCommand body,
            HttpServletRequest request) {
        return service.save(CustodyRequestSupport.requirePrincipal(request), body,
                CustodyRequestSupport.clientIp(request));
    }
}
