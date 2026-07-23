package com.surprising.wallet.jobs.custody;

import com.surprising.wallet.jobs.custody.CustodyAddressService.AddressView;
import com.surprising.wallet.jobs.custody.CustodyAddressService.CreateAddressCommand;
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

@RestController
@RequestMapping("/custody/api/v1")
public class CustodyPublicApiController {
    private final CustodyAddressService addresses;
    private final CustodyWithdrawalService transfers;
    private final CustodyTenantChainService chains;

    public CustodyPublicApiController(CustodyAddressService addresses,
                                      CustodyWithdrawalService transfers,
                                      CustodyTenantChainService chains) {
        this.addresses = addresses;
        this.transfers = transfers;
        this.chains = chains;
    }

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

    @GetMapping("/assets")
    public List<Map<String, Object>> assets(HttpServletRequest request) {
        return addresses.assets(CustodyRequestSupport.requirePrincipal(request));
    }

    @GetMapping("/chains")
    public List<CustodyTenantChainService.ChainView> chains(HttpServletRequest request) {
        return chains.list(CustodyRequestSupport.requirePrincipal(request)).stream()
                .filter(CustodyTenantChainService.ChainView::enabled)
                .toList();
    }

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

    public record CreatePublicAddressRequest(
            String chainId,
            String subject,
            Long addressVersion
    ) {
    }
}
