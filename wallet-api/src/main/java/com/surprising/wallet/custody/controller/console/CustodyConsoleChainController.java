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
    /** 链配置服务，查询/更新租户链启用开关。 */
    private final CustodyTenantChainService chains;

    /**
     * 注入链服务。
     */
    public CustodyConsoleChainController(CustodyTenantChainService chains) {
        this.chains = chains;
    }

    /**
     * 获取当前租户链列表与链级配置。
     */
    @GetMapping
    public List<CustodyTenantChainService.ChainView> list(HttpServletRequest request) {
        return chains.list(CustodyRequestSupport.requirePrincipal(request));
    }

    /**
     * 按链标识更新启用状态。
     */
    @PutMapping("/{chain}")
    public CustodyTenantChainService.ChainView setEnabled(
            @PathVariable String chain,
            @RequestBody UpdateTenantChainRequest body,
            HttpServletRequest request) {
        return chains.setEnabled(CustodyRequestSupport.requirePrincipal(request), chain,
                body.enabled(), CustodyRequestSupport.clientIp(request));
    }

    /**
     * 链启用开关请求体。
     */
    public record UpdateTenantChainRequest(boolean enabled) {
    }
}
