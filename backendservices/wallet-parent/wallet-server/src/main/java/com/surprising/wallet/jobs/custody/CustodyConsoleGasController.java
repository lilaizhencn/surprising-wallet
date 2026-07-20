package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.jobs.custody.CustodyGasService.CreateGasAccountCommand;
import com.surprising.wallet.jobs.custody.CustodyGasService.GasAccountView;
import com.surprising.wallet.jobs.custody.CustodyGasService.UpdateGasAccountCommand;
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

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleGasController {
    private final CustodyGasService gas;

    public CustodyConsoleGasController(CustodyGasService gas) {
        this.gas = gas;
    }

    @GetMapping("/gas-accounts")
    public List<GasAccountView> accounts(HttpServletRequest request) {
        return gas.list(CustodyRequestSupport.requirePrincipal(request));
    }

    @PostMapping("/gas-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public GasAccountView create(@RequestBody CreateGasAccountCommand body,
                                 HttpServletRequest request) {
        return gas.create(
                CustodyRequestSupport.requirePrincipal(request),
                body,
                CustodyRequestSupport.clientIp(request));
    }

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

    @GetMapping("/onboarding")
    public Map<String, Object> onboarding(HttpServletRequest request) {
        return gas.onboarding(CustodyRequestSupport.requirePrincipal(request));
    }
}
