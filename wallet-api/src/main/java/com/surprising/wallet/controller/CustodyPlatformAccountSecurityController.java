package com.surprising.wallet.controller;

import com.surprising.wallet.model.CustodyRequestSupport;
import com.surprising.wallet.service.CustodyLoginSecurityService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/custody/platform/v1/tenants/{tenant}/administrators/{id}")
@RequiredArgsConstructor
public class CustodyPlatformAccountSecurityController {
    private final CustodyLoginSecurityService security;
    @PostMapping("/reset-totp")
    public Map<String,Boolean> recover(@PathVariable UUID tenant,@PathVariable UUID id,@RequestBody Reason body,HttpServletRequest request) {
        security.recoverAdministrator(CustodyRequestSupport.requirePrincipal(request),tenant,id,body.reason(),CustodyRequestSupport.clientIp(request));
        return Map.of("ok",true);
    }
    public record Reason(String reason) { }
}
