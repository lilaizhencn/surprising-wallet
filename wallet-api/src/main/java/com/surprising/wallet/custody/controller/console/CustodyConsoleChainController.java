package com.surprising.wallet.custody.controller.console;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.service.CustodyTenantChainService;

@RestController
@RequestMapping("/custody/console/v1/chains")
public class CustodyConsoleChainController {
    private final CustodyTenantChainService chains;

    public CustodyConsoleChainController(CustodyTenantChainService chains) {
        this.chains = chains;
    }

    @GetMapping
    public List<CustodyTenantChainService.ChainView> list(HttpServletRequest request) {
        return chains.list(CustodyRequestSupport.requirePrincipal(request));
    }

    @PutMapping("/{chain}")
    public CustodyTenantChainService.ChainView setEnabled(
            @PathVariable String chain,
            @RequestBody UpdateTenantChainRequest body,
            HttpServletRequest request) {
        return chains.setEnabled(CustodyRequestSupport.requirePrincipal(request), chain,
                body.enabled(), CustodyRequestSupport.clientIp(request));
    }

    public record UpdateTenantChainRequest(boolean enabled) {
    }
}
