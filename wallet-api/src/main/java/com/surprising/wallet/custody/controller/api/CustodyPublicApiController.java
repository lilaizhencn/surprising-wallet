package com.surprising.wallet.custody.controller.api;

import com.surprising.wallet.custody.service.CustodyAddressService.AddressView;
import com.surprising.wallet.custody.service.CustodyAddressService.CreateAddressCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

import com.surprising.wallet.custody.service.CustodyAddressService;
import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.service.CustodyTenantChainService;
import com.surprising.wallet.custody.service.CustodyWithdrawalService;

@RestController
@RequestMapping("/custody/api/v1")
public class CustodyPublicApiController {
    /** 地址服务：负责创建与查询托管地址。 */
    private final CustodyAddressService addresses;
    /** 提现/充值服务：负责创建提现与查询明细。 */
    private final CustodyWithdrawalService transfers;
    /** 可用链服务：返回当前租户启用链。 */
    private final CustodyTenantChainService chains;

    /**
     * 依赖注入入口。
     */
    public CustodyPublicApiController(CustodyAddressService addresses,
                                      CustodyWithdrawalService transfers,
                                      CustodyTenantChainService chains) {
        this.addresses = addresses;
        this.transfers = transfers;
        this.chains = chains;
    }

    /**
     * 创建托管地址，用于 API 场景下的入账地址派生。
     */
    @PostMapping("/addresses")
    public AddressView createAddress(
            @RequestBody CreatePublicAddressRequest body,
            HttpServletRequest request) {
        return addresses.create(
                CustodyRequestSupport.requirePrincipal(request),
                new CreateAddressCommand(
                        body.chainId(), body.subject(), body.addressVersion(), null, null),
                "API",
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 分页查询托管地址，支持链/状态/关键字过滤。
     */
    @GetMapping("/addresses")
    public List<AddressView> addresses(
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return addresses.list(CustodyRequestSupport.requirePrincipal(request),
                chain, "", status, search, limit, offset);
    }

    /**
     * 查询当前租户资产列表（可见资产、可用余额、配置状态）。
     */
    @GetMapping("/assets")
    public List<Map<String, Object>> assets(HttpServletRequest request) {
        return addresses.assets(CustodyRequestSupport.requirePrincipal(request));
    }

    /**
     * 查询当前租户已启用链的集合，过滤未启用项。
     */
    @GetMapping("/chains")
    public List<CustodyTenantChainService.ChainView> chains(HttpServletRequest request) {
        return chains.list(CustodyRequestSupport.requirePrincipal(request)).stream()
                .filter(CustodyTenantChainService.ChainView::enabled)
                .toList();
    }

    /**
     * 分页查询充值记录，支持链/币种/状态/关键词过滤。
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
     * 分页查询提现记录，支持链/币种/状态/关键词过滤。
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
     * 新建提现请求，入库后进入提现等待队列。
     */
    @PostMapping("/withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    public CustodyWithdrawalService.WithdrawalView createWithdrawal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CustodyWithdrawalService.CreateWithdrawalCommand body,
            HttpServletRequest request) {
        return transfers.create(
                CustodyRequestSupport.requirePrincipal(request), body, "API", idempotencyKey,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * API 创建地址请求参数快照。
     */
    public record CreatePublicAddressRequest(
            String chainId,
            String subject,
            Long addressVersion
    ) {
    }
}
