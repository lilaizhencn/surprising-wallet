package com.surprising.wallet.custody.controller.platform;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
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

import com.surprising.wallet.custody.model.CustodyPrincipal;
import com.surprising.wallet.custody.model.CustodyRequestSupport;
import com.surprising.wallet.custody.service.WalletConfigManagementService;

@RestController
@RequestMapping("/custody/platform/v1/wallet-config")
public class WalletConfigManagementController {
    /** 平台钱包配置服务。 */
    private final WalletConfigManagementService service;

    /**
     * 注入配置服务。
     */
    public WalletConfigManagementController(WalletConfigManagementService service) {
        this.service = service;
    }

    /**
     * 查询链配置，支持多条件过滤。
     */
    @GetMapping("/chains")
    public List<WalletConfigManagementService.ChainView> chains(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "") String network,
            @RequestParam(defaultValue = "") String family,
            @RequestParam(defaultValue = "") String token,
            @RequestParam(required = false) Boolean scanEnabled,
            @RequestParam(required = false) Boolean withdrawEnabled,
            @RequestParam(required = false) Boolean collectionEnabled,
            @RequestParam(required = false) Boolean transferEnabled,
            HttpServletRequest request) {
        return service.listChains(principal(request), search, enabled, network, family, token,
                scanEnabled, withdrawEnabled, collectionEnabled, transferEnabled);
    }

    /**
     * 查询单条链配置。
     */
    @GetMapping("/chains/{id}")
    public WalletConfigManagementService.ChainDetailView chain(
            @PathVariable long id, HttpServletRequest request) {
        return service.getChain(principal(request), id);
    }

    /**
     * 创建链配置。
     */
    @PostMapping("/chains")
    public WalletConfigManagementService.ChainDetailView createChain(
            @RequestBody WalletConfigManagementService.ChainCommand body,
            HttpServletRequest request) {
        return service.createChain(principal(request), body, ip(request));
    }

    /**
     * 更新链基础配置。
     */
    @PatchMapping("/chains/{id}")
    public WalletConfigManagementService.ChainDetailView updateChain(
            @PathVariable long id,
            @RequestBody WalletConfigManagementService.ChainCommand body,
            HttpServletRequest request) {
        return service.updateChain(principal(request), id, body, ip(request));
    }

    /**
     * 更新链开关配置。
     */
    @PatchMapping("/chains/{id}/switches")
    public WalletConfigManagementService.ChainDetailView updateChainSwitches(
            @PathVariable long id,
            @RequestBody WalletConfigManagementService.ChainSwitchCommand body,
            HttpServletRequest request) {
        return service.updateChainSwitches(principal(request), id, body, ip(request));
    }

    /**
     * 查询链下 RPC 节点列表。
     */
    @GetMapping("/chains/{id}/rpc-nodes")
    public List<WalletConfigManagementService.RpcNodeView> rpcNodes(
            @PathVariable long id, HttpServletRequest request) {
        return service.listRpcNodes(principal(request), id);
    }

    /**
     * 创建链 RPC 节点。
     */
    @PostMapping("/chains/{id}/rpc-nodes")
    public WalletConfigManagementService.RpcNodeView createRpcNode(
            @PathVariable long id,
            @RequestBody WalletConfigManagementService.RpcNodeCommand body,
            HttpServletRequest request) {
        return service.createRpcNode(principal(request), id, body, ip(request));
    }

    /**
     * 更新 RPC 节点。
     */
    @PatchMapping("/chains/{id}/rpc-nodes/{nodeId}")
    public WalletConfigManagementService.RpcNodeView updateRpcNode(
            @PathVariable long id, @PathVariable long nodeId,
            @RequestBody WalletConfigManagementService.RpcNodeCommand body,
            HttpServletRequest request) {
        return service.updateRpcNode(principal(request), id, nodeId, body, ip(request));
    }

    /**
     * 删除 RPC 节点。
     */
    @DeleteMapping("/chains/{id}/rpc-nodes/{nodeId}")
    public Map<String, Object> deleteRpcNode(
            @PathVariable long id, @PathVariable long nodeId, HttpServletRequest request) {
        service.deleteRpcNode(principal(request), id, nodeId, ip(request));
        return Map.of("deleted", true, "id", nodeId);
    }

    /**
     * 测试 RPC 节点连通性。
     */
    @PostMapping("/chains/{id}/rpc-nodes/{nodeId}/test")
    public WalletConfigManagementService.RpcTestView testRpcNode(
            @PathVariable long id, @PathVariable long nodeId, HttpServletRequest request) {
        return service.testRpcNode(principal(request), id, nodeId);
    }

    /**
     * 查询 token 列表。
     */
    @GetMapping("/tokens")
    public List<WalletConfigManagementService.TokenView> tokens(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String network,
            @RequestParam(defaultValue = "") String standard,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean effectiveEnabled,
            HttpServletRequest request) {
        return service.listTokens(principal(request), search, chain, network, standard,
                enabled, effectiveEnabled);
    }

    /**
     * 查询 token 矩阵（无分页）。
     */
    @GetMapping("/tokens/matrix")
    public List<WalletConfigManagementService.TokenView> tokenMatrix(
            @RequestParam(defaultValue = "") String search, HttpServletRequest request) {
        return service.listTokens(principal(request), search, "", "", "", null, null);
    }

    /**
     * 创建 token 配置。
     */
    @PostMapping("/tokens")
    public WalletConfigManagementService.TokenView createToken(
            @RequestBody WalletConfigManagementService.TokenCommand body,
            HttpServletRequest request) {
        return service.createToken(principal(request), body, ip(request));
    }

    /**
     * 更新 token 配置。
     */
    @PatchMapping("/tokens/{id}")
    public WalletConfigManagementService.TokenView updateToken(
            @PathVariable long id,
            @RequestBody WalletConfigManagementService.TokenCommand body,
            HttpServletRequest request) {
        return service.updateToken(principal(request), id, body, ip(request));
    }

    /**
     * 更新 token 状态。
     */
    @PatchMapping("/tokens/{id}/status")
    public WalletConfigManagementService.TokenView updateTokenStatus(
            @PathVariable long id,
            @RequestBody WalletConfigManagementService.TokenStatusCommand body,
            HttpServletRequest request) {
        return service.updateTokenStatus(principal(request), id, body, ip(request));
    }

    /**
     * 查询审计日志。
     */
    @GetMapping("/audit-log")
    public List<Map<String, Object>> auditLog(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return service.auditLog(principal(request), limit, offset);
    }

    /**
     * 解析请求中的租户主体。
     */
    private static CustodyPrincipal principal(HttpServletRequest request) {
        return CustodyRequestSupport.requirePrincipal(request);
    }

    /**
     * 解析请求来源 IP。
     */
    private static String ip(HttpServletRequest request) {
        return CustodyRequestSupport.clientIp(request);
    }
}
