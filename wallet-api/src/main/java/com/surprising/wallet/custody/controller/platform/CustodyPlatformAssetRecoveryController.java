package com.surprising.wallet.custody.controller.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import com.surprising.wallet.custody.repository.CustodyAssetRecoveryRepository;
import com.surprising.wallet.custody.service.CustodyAssetRecoveryService;
import com.surprising.wallet.custody.model.CustodyRequestSupport;

@RestController
@RequestMapping("/custody/platform/v1/asset-recoveries")
public class CustodyPlatformAssetRecoveryController {
    private final CustodyAssetRecoveryService recoveries;

    public CustodyPlatformAssetRecoveryController(CustodyAssetRecoveryService recoveries) {
        this.recoveries = recoveries;
    }

    @GetMapping
    public List<CustodyAssetRecoveryRepository.RecoveryRecord> list(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return recoveries.platformList(
                CustodyRequestSupport.requirePrincipal(request), status, limit, offset);
    }

    @PostMapping("/{id}/verify")
    public CustodyAssetRecoveryRepository.RecoveryRecord verify(
            @PathVariable UUID id, HttpServletRequest request) {
        return recoveries.verify(CustodyRequestSupport.requirePrincipal(request), id,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{id}/approve")
    public CustodyAssetRecoveryRepository.RecoveryRecord approve(
            @PathVariable UUID id,
            @RequestBody CustodyAssetRecoveryService.ApproveCommand body,
            HttpServletRequest request) {
        return recoveries.approve(CustodyRequestSupport.requirePrincipal(request), id, body,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{id}/execute")
    public CustodyAssetRecoveryRepository.RecoveryRecord execute(
            @PathVariable UUID id, HttpServletRequest request) {
        return recoveries.execute(CustodyRequestSupport.requirePrincipal(request), id,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{id}/confirm")
    public CustodyAssetRecoveryRepository.RecoveryRecord confirm(
            @PathVariable UUID id, HttpServletRequest request) {
        return recoveries.confirm(CustodyRequestSupport.requirePrincipal(request), id,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{id}/reject")
    public CustodyAssetRecoveryRepository.RecoveryRecord reject(
            @PathVariable UUID id,
            @RequestBody CustodyAssetRecoveryService.RejectCommand body,
            HttpServletRequest request) {
        return recoveries.reject(CustodyRequestSupport.requirePrincipal(request), id, body,
                CustodyRequestSupport.clientIp(request));
    }
}
