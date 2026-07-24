package com.surprising.wallet.custody.controller.console;

import com.surprising.wallet.custody.service.CustodyWithdrawalService.CreateWithdrawalCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.service.CustodyWithdrawalService;

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleTransferController {
    /** 提现服务：查询与提交提现工单。 */
    private final CustodyWithdrawalService transfers;

    /**
     * 注入提现服务。
     */
    public CustodyConsoleTransferController(CustodyWithdrawalService transfers) {
        this.transfers = transfers;
    }

    /**
     * 控制台分页查询充值记录。
     */
    @GetMapping("/deposits")
    public List<Map<String, Object>> deposits(
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String assetSymbol,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return transfers.deposits(
                CustodyRequestSupport.requirePrincipal(request),
                chain, assetSymbol, status, search, limit, offset);
    }

    /**
     * 控制台分页查询提现记录。
     */
    @GetMapping("/withdrawals")
    public List<Map<String, Object>> withdrawals(
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String assetSymbol,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return transfers.withdrawals(
                CustodyRequestSupport.requirePrincipal(request),
                chain, assetSymbol, status, search, limit, offset);
    }

    /**
     * 提交控制台提现请求，来源打标记为 CONSOLE。
     */
    @PostMapping("/withdrawals")
    public CustodyWithdrawalService.WithdrawalView create(
            @RequestBody CreateWithdrawalCommand body,
            HttpServletRequest request) {
        return transfers.create(
                CustodyRequestSupport.requirePrincipal(request), body, "CONSOLE", null,
                CustodyRequestSupport.clientIp(request));
    }
}
