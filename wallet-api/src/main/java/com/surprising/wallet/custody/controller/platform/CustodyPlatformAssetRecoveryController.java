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
    /** 平台级找回服务，支持运维审核和执行。 */
    private final CustodyAssetRecoveryService recoveries;

    /**
     * 注入找回服务。
     */
    public CustodyPlatformAssetRecoveryController(CustodyAssetRecoveryService recoveries) {
        this.recoveries = recoveries;
    }

    /**
     * 平台查询所有找回工单，支持按状态过滤。
     */
    @GetMapping
    public List<CustodyAssetRecoveryRepository.RecoveryRecord> list(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return recoveries.platformList(
                CustodyRequestSupport.requirePrincipal(request), status, limit, offset);
    }

    /**
     * 平台审核找回工单。
     */
    @PostMapping("/{id}/verify")
    public CustodyAssetRecoveryRepository.RecoveryRecord verify(
            @PathVariable UUID id, HttpServletRequest request) {
        return recoveries.verify(CustodyRequestSupport.requirePrincipal(request), id,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 平台审批并提交执行参数。
     */
    @PostMapping("/{id}/approve")
    public CustodyAssetRecoveryRepository.RecoveryRecord approve(
            @PathVariable UUID id,
            @RequestBody CustodyAssetRecoveryService.ApproveCommand body,
            HttpServletRequest request) {
        return recoveries.approve(CustodyRequestSupport.requirePrincipal(request), id, body,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 平台触发执行找回交易上链流程。
     */
    @PostMapping("/{id}/execute")
    public CustodyAssetRecoveryRepository.RecoveryRecord execute(
            @PathVariable UUID id, HttpServletRequest request) {
        return recoveries.execute(CustodyRequestSupport.requirePrincipal(request), id,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 平台确认找回交易结果并关闭工单。
     */
    @PostMapping("/{id}/confirm")
    public CustodyAssetRecoveryRepository.RecoveryRecord confirm(
            @PathVariable UUID id, HttpServletRequest request) {
        return recoveries.confirm(CustodyRequestSupport.requirePrincipal(request), id,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 平台驳回找回工单。
     */
    @PostMapping("/{id}/reject")
    public CustodyAssetRecoveryRepository.RecoveryRecord reject(
            @PathVariable UUID id,
            @RequestBody CustodyAssetRecoveryService.RejectCommand body,
            HttpServletRequest request) {
        return recoveries.reject(CustodyRequestSupport.requirePrincipal(request), id, body,
                CustodyRequestSupport.clientIp(request));
    }
}
