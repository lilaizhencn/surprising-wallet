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

    public CustodyPublicApiController(CustodyAddressService addresses,
                                      CustodyWithdrawalService transfers) {
        this.addresses = addresses;
        this.transfers = transfers;
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public AddressView createAddress(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CreateAddressCommand body,
            HttpServletRequest request) {
        return addresses.create(
                CustodyRequestSupport.requirePrincipal(request),
                body,
                "API",
                idempotencyKey,
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
    @ResponseStatus(HttpStatus.CREATED)
    public CustodyWithdrawalService.WithdrawalView createWithdrawal(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody CustodyWithdrawalService.CreateWithdrawalCommand body,
            HttpServletRequest request) {
        return transfers.create(
                CustodyRequestSupport.requirePrincipal(request), body, "API", idempotencyKey,
                CustodyRequestSupport.clientIp(request));
    }
}
