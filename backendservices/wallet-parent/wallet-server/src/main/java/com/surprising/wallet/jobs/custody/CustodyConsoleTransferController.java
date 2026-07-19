package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.jobs.custody.CustodyWithdrawalService.CreateWithdrawalCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleTransferController {
    private final CustodyWithdrawalService transfers;

    public CustodyConsoleTransferController(CustodyWithdrawalService transfers) {
        this.transfers = transfers;
    }

    @GetMapping("/deposits")
    public List<Map<String, Object>> deposits(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return transfers.deposits(
                CustodyRequestSupport.requirePrincipal(request), status, limit, offset);
    }

    @GetMapping("/withdrawals")
    public List<Map<String, Object>> withdrawals(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return transfers.withdrawals(
                CustodyRequestSupport.requirePrincipal(request), status, limit, offset);
    }

    @PostMapping("/withdrawals")
    public CustodyWithdrawalService.WithdrawalView create(
            @RequestBody CreateWithdrawalCommand body,
            HttpServletRequest request) {
        return transfers.create(
                CustodyRequestSupport.requirePrincipal(request), body, "CONSOLE", null,
                CustodyRequestSupport.clientIp(request));
    }
}
