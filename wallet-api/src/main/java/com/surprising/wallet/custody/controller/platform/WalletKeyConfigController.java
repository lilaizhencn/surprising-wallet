package com.surprising.wallet.custody.controller.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.service.WalletKeyConfigService;

/**
 * 平台密钥配置控制器。
 *
 * <p>端点路径：/custody/platform/v1/wallet-config/keyset。
 * 提供 BIP32 密钥材料的状态查询和轮换功能，需要平台管理员权限。
 */
@RestController
@RequestMapping("/custody/platform/v1/wallet-config/keyset")
public class WalletKeyConfigController {
    /** 密钥服务。 */
    private final WalletKeyConfigService service;

    /**
     * 注入密钥服务。
     */
    public WalletKeyConfigController(WalletKeyConfigService service) {
        this.service = service;
    }

    /**
     * 查询当前有效密钥材料视图。
     */
    @GetMapping
    public WalletKeyConfigService.KeysetView get(HttpServletRequest request) {
        return service.get(CustodyRequestSupport.requirePrincipal(request));
    }

    /**
     * 覆盖保存密钥配置（审计与生效记录）。
     */
    @PutMapping
    public WalletKeyConfigService.KeysetView save(
            @RequestBody WalletKeyConfigService.SaveKeysetCommand body,
            HttpServletRequest request) {
        return service.save(CustodyRequestSupport.requirePrincipal(request), body,
                CustodyRequestSupport.clientIp(request));
    }
}
