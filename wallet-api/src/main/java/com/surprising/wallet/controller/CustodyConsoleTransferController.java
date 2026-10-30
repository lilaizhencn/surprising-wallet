package com.surprising.wallet.controller;

import com.surprising.wallet.service.CustodyWithdrawalService.CreateWithdrawalCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.surprising.wallet.model.PageView;
import com.surprising.wallet.model.CustodyRequestSupport;
import com.surprising.wallet.service.CustodyWithdrawalService;
import com.surprising.wallet.service.CustodyTransactionQueryService;

/**
 * Console 提现/转账管理控制器。
 *
 * <p>端点路径：/custody/console/v1/{tenantId}/withdrawals。
 * 提供提现工单的创建（POST）和查询列表（GET）功能。
 */
@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleTransferController {
    /** 提现创建用例。 */
    private final CustodyWithdrawalService transfers;
    private final CustodyTransactionQueryService history;

    /**
     * 注入提现服务。
     */
    public CustodyConsoleTransferController(CustodyWithdrawalService transfers,
                                            CustodyTransactionQueryService history) {
        this.transfers = transfers;
        this.history = history;
    }

    /**
     * 控制台分页查询充值记录。
     */
    @GetMapping("/deposits")
    public PageView<CustodyTransactionQueryService.DepositView> deposits(
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String assetSymbol,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return history.depositPage(
                CustodyRequestSupport.requirePrincipal(request),
                chain, assetSymbol, status, search, limit, offset);
    }

    /**
     * 控制台分页查询提现记录。
     */
    @GetMapping("/withdrawals")
    public PageView<CustodyTransactionQueryService.WithdrawalView> withdrawals(
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String assetSymbol,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return history.withdrawalPage(
                CustodyRequestSupport.requirePrincipal(request),
                chain, assetSymbol, status, search, limit, offset);
    }

    /**
     * 提交控制台提现请求，来源打标记为 CONSOLE。
    */
    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CustodyWithdrawalService.WithdrawalView create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateWithdrawalCommand body,
            HttpServletRequest request) {
        return transfers.create(
                CustodyRequestSupport.requirePrincipal(request), body, "CONSOLE", idempotencyKey,
                CustodyRequestSupport.clientIp(request));
    }

    @GetMapping("/withdrawals/submission")
    public java.util.List<CustodyTransactionQueryService.WithdrawalView> submission(
            @RequestParam String reference, HttpServletRequest request) {
        return history.withdrawalSubmission(CustodyRequestSupport.requirePrincipal(request),reference);
    }

    @PostMapping("/withdrawals/resolve")
    public CustodyWithdrawalService.SubmissionResolution resolve(
            @RequestHeader("Idempotency-Key") String key, @RequestBody CreateWithdrawalCommand body,
            HttpServletRequest request) {
        return transfers.resolve(CustodyRequestSupport.requirePrincipal(request),body,key,CustodyRequestSupport.clientIp(request));
    }
}
