package com.surprising.wallet.custody.controller.console;

import com.surprising.wallet.custody.service.CustodyAddressService.AddressView;
import com.surprising.wallet.custody.service.CustodyAddressService.CreateAddressCommand;
import com.surprising.wallet.custody.service.CustodyAddressService.UpdateAddressCommand;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.surprising.wallet.custody.service.CustodyAddressService;
import com.surprising.wallet.custody.model.CustodyRequestSupport;

@RestController
@RequestMapping("/custody/console/v1")
public class CustodyConsoleAddressController {
    private final CustodyAddressService addresses;

    public CustodyConsoleAddressController(CustodyAddressService addresses) {
        this.addresses = addresses;
    }

    @GetMapping("/addresses")
    public List<AddressView> addresses(
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return addresses.list(CustodyRequestSupport.requirePrincipal(request),
                chain, source, status, search, limit, offset);
    }

    @PostMapping("/addresses")
    public AddressView create(@RequestBody CreateAddressCommand body, HttpServletRequest request) {
        return addresses.create(
                CustodyRequestSupport.requirePrincipal(request),
                body,
                "CONSOLE",
                CustodyRequestSupport.clientIp(request));
    }

    @PatchMapping("/addresses/{addressId}")
    public AddressView update(@PathVariable UUID addressId,
                              @RequestBody UpdateAddressCommand body,
                              HttpServletRequest request) {
        return addresses.update(
                CustodyRequestSupport.requirePrincipal(request),
                addressId,
                body,
                CustodyRequestSupport.clientIp(request));
    }

    @GetMapping("/assets")
    public List<Map<String, Object>> assets(HttpServletRequest request) {
        return addresses.assets(CustodyRequestSupport.requirePrincipal(request));
    }
}
