package com.surprising.wallet.controller;

import com.surprising.wallet.model.CustodyRequestSupport;
import com.surprising.wallet.model.custody.TenantUserView;
import com.surprising.wallet.service.CustodyTenantUserService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/custody/console/v1/users")
@RequiredArgsConstructor
public class CustodyConsoleTenantUserController {
    private final CustodyTenantUserService members;

    @GetMapping
    public List<TenantUserView> list(HttpServletRequest request) {
        return members.list(CustodyRequestSupport.requirePrincipal(request));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TenantUserView create(@RequestBody CustodyTenantUserService.CreateMember body, HttpServletRequest request) {
        return members.create(CustodyRequestSupport.requirePrincipal(request), body, CustodyRequestSupport.clientIp(request));
    }

    @PutMapping("/{id}")
    public TenantUserView update(@PathVariable UUID id, @RequestBody CustodyTenantUserService.UpdateMember body, HttpServletRequest request) {
        return members.update(CustodyRequestSupport.requirePrincipal(request), id, body, CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{id}/reset-password")
    public Map<String, Boolean> reset(@PathVariable UUID id, @RequestBody ResetPassword body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("password is required");
        members.resetPassword(CustodyRequestSupport.requirePrincipal(request), id, body.password(), CustodyRequestSupport.clientIp(request));
        return Map.of("ok", true);
    }

    public record ResetPassword(String password) { }
}
