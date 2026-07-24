package com.surprising.wallet.custody.controller.console;

import com.surprising.wallet.custody.service.CustodyGasService.CreateGasAccountCommand;
import com.surprising.wallet.custody.service.CustodyGasService.GasAccountView;
import com.surprising.wallet.custody.service.CustodyGasService.UpdateGasAccountCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.service.CustodyGasService;
import com.surprising.wallet.custody.model.CustodyRequestSupport;

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleGasController {
    /** Gas 管理服务。 */
    private final CustodyGasService gas;

    /**
     * 注入 gas 服务。
     */
    public CustodyConsoleGasController(CustodyGasService gas) {
        this.gas = gas;
    }

    /**
     * 查询租户下 gas 账户列表。
     */
    @GetMapping("/gas-accounts")
    public List<GasAccountView> accounts(HttpServletRequest request) {
        return gas.list(CustodyRequestSupport.requirePrincipal(request));
    }

    /**
     * 新建 gas 账户（链上 gas 充值/扣费统一管理）。
     */
    @PostMapping("/gas-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public GasAccountView create(@RequestBody CreateGasAccountCommand body,
                                 HttpServletRequest request) {
        return gas.create(
                CustodyRequestSupport.requirePrincipal(request),
                body,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 更新 gas 账户配置（额度、策略、启停等）。
     */
    @PatchMapping("/gas-accounts/{gasAccountId}")
    public GasAccountView update(@PathVariable UUID gasAccountId,
                                 @RequestBody UpdateGasAccountCommand body,
                                 HttpServletRequest request) {
        return gas.update(
                CustodyRequestSupport.requirePrincipal(request),
                gasAccountId,
                body,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 查询 gas 充值记录。
     */
    @GetMapping("/gas-accounts/{gasAccountId}/topups")
    public List<Map<String, Object>> topups(
            @PathVariable UUID gasAccountId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return gas.topups(
                CustodyRequestSupport.requirePrincipal(request),
                gasAccountId,
                limit,
                offset);
    }

    /**
     * 查询 gas 使用明细与余额消耗统计。
     */
    @GetMapping("/gas-accounts/{gasAccountId}/usage")
    public List<Map<String, Object>> usage(
            @PathVariable UUID gasAccountId,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return gas.usage(
                CustodyRequestSupport.requirePrincipal(request),
                gasAccountId,
                limit,
                offset);
    }

    /**
     * 查询 gas 上手册信息（最小充值、链支持、常见提示）。
     */
    @GetMapping("/onboarding")
    public Map<String, Object> onboarding(HttpServletRequest request) {
        return gas.onboarding(CustodyRequestSupport.requirePrincipal(request));
    }
}
