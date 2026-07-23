package com.surprising.wallet.jobs.custody;

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

@RestController
@RequestMapping("/custody/console/v1/asset-recoveries")
public class CustodyConsoleAssetRecoveryController {
    private final CustodyAssetRecoveryService recoveries;

    public CustodyConsoleAssetRecoveryController(CustodyAssetRecoveryService recoveries) {
        this.recoveries = recoveries;
    }

    @GetMapping
    public List<CustodyAssetRecoveryRepository.RecoveryRecord> list(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return recoveries.tenantList(
                CustodyRequestSupport.requirePrincipal(request), status, limit, offset);
    }

    @PostMapping
    public CustodyAssetRecoveryRepository.RecoveryRecord submit(
            @RequestBody CustodyAssetRecoveryService.SubmitCommand body,
            HttpServletRequest request) {
        return recoveries.submit(CustodyRequestSupport.requirePrincipal(request), body,
                CustodyRequestSupport.clientIp(request));
    }

    @PostMapping("/{id}/cancel")
    public CustodyAssetRecoveryRepository.RecoveryRecord cancel(
            @PathVariable UUID id, HttpServletRequest request) {
        return recoveries.cancel(CustodyRequestSupport.requirePrincipal(request), id,
                CustodyRequestSupport.clientIp(request));
    }
}
