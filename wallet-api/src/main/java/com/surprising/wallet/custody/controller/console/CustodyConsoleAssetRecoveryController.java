package com.surprising.wallet.custody.controller.console;

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

/**
 * Console 资产找回控制器。
 *
 * <p>端点路径：/custody/console/v1/{tenantId}/asset-recoveries。
 * 提供误转入资产找回请求的创建（POST）和查询列表（GET）功能。
 */
@RestController
@RequestMapping("/custody/console/v1/asset-recoveries")
public class CustodyConsoleAssetRecoveryController {
    /** 资产找回服务，用于提交、查询和取消找回工单。 */
    private final CustodyAssetRecoveryService recoveries;

    /**
     * 注入资产找回服务。
     */
    public CustodyConsoleAssetRecoveryController(CustodyAssetRecoveryService recoveries) {
        this.recoveries = recoveries;
    }

    /**
     * 按状态分页查询当前租户找回记录。
     */
    @GetMapping
    public List<CustodyAssetRecoveryRepository.RecoveryRecord> list(
            @RequestParam(defaultValue = "") String status,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return recoveries.tenantList(
                CustodyRequestSupport.requirePrincipal(request), status, limit, offset);
    }

    /**
     * 提交一笔资产找回申请并返回创建后的记录。
     */
    @PostMapping
    public CustodyAssetRecoveryRepository.RecoveryRecord submit(
            @RequestBody CustodyAssetRecoveryService.SubmitCommand body,
            HttpServletRequest request) {
        return recoveries.submit(CustodyRequestSupport.requirePrincipal(request), body,
                CustodyRequestSupport.clientIp(request));
    }

    /**
     * 取消未执行或可取消状态的找回申请。
     */
    @PostMapping("/{id}/cancel")
    public CustodyAssetRecoveryRepository.RecoveryRecord cancel(
            @PathVariable UUID id, HttpServletRequest request) {
        return recoveries.cancel(CustodyRequestSupport.requirePrincipal(request), id,
                CustodyRequestSupport.clientIp(request));
    }
}
