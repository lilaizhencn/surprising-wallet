package com.surprising.wallet.controller;

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

import com.surprising.wallet.model.PageView;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.model.CustodyRequestSupport;
import com.surprising.wallet.service.WalletChainConfigService;
import com.surprising.wallet.service.WalletRpcConfigService;
import com.surprising.wallet.service.WalletTokenConfigService;
import com.surprising.wallet.service.WalletConfigOverviewService;

/**
 * 平台钱包配置管理控制器。
 *
 * <p>端点路径：/custody/platform/v1/wallet-config。
 * 按链、RPC 节点和 Token 委托配置用例，并提供平台配置审计查询。
 */
@RestController
@RequestMapping("/custody/platform/v1/wallet-config")
public class WalletConfigManagementController {
    private final WalletChainConfigService chains;
    private final WalletRpcConfigService rpc;
    private final WalletTokenConfigService tokens;
    private final WalletConfigOverviewService overview;

    public WalletConfigManagementController(WalletChainConfigService chains, WalletRpcConfigService rpc,
                                            WalletTokenConfigService tokens, WalletConfigOverviewService overview) {
        this.chains = chains;
        this.rpc = rpc;
        this.tokens = tokens;
        this.overview = overview;
    }

    /**
     * 查询链配置，支持多条件过滤。
     */
    @GetMapping("/chains")
    public List<WalletChainConfigService.ChainView> chains(
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
        return chains.listChains(principal(request), search, enabled, network, family, token,
                scanEnabled, withdrawEnabled, collectionEnabled, transferEnabled);
    }

    /**
     * 查询单条链配置。
     */
    @GetMapping("/chains/{id}")
    public WalletChainConfigService.ChainDetailView chain(
            @PathVariable long id, HttpServletRequest request) {
        return chains.getChain(principal(request), id);
    }

    /**
     * 创建链配置。
     */
    @PostMapping("/chains")
    public WalletChainConfigService.ChainDetailView createChain(
            @RequestBody WalletChainConfigService.ChainCommand body,
            HttpServletRequest request) {
        return chains.createChain(principal(request), body, ip(request));
    }

    /**
     * 更新链基础配置。
     */
    @PatchMapping("/chains/{id}")
    public WalletChainConfigService.ChainDetailView updateChain(
            @PathVariable long id,
            @RequestBody WalletChainConfigService.ChainCommand body,
            HttpServletRequest request) {
        return chains.updateChain(principal(request), id, body, ip(request));
    }

    /**
     * 更新链开关配置。
     */
    @PatchMapping("/chains/{id}/switches")
    public WalletChainConfigService.ChainDetailView updateChainSwitches(
            @PathVariable long id,
            @RequestBody WalletChainConfigService.ChainSwitchCommand body,
            HttpServletRequest request) {
        return chains.updateChainSwitches(principal(request), id, body, ip(request));
    }

    /**
     * 查询链下 RPC 节点列表。
     */
    @GetMapping("/chains/{id}/rpc-nodes")
    public List<WalletRpcConfigService.RpcNodeView> rpcNodes(
            @PathVariable long id, HttpServletRequest request) {
        return rpc.listRpcNodes(principal(request), id);
    }

    /**
     * 创建链 RPC 节点。
     */
    @PostMapping("/chains/{id}/rpc-nodes")
    public WalletRpcConfigService.RpcNodeView createRpcNode(
            @PathVariable long id,
            @RequestBody WalletRpcConfigService.RpcNodeCommand body,
            HttpServletRequest request) {
        return rpc.createRpcNode(principal(request), id, body, ip(request));
    }

    /**
     * 更新 RPC 节点。
     */
    @PatchMapping("/chains/{id}/rpc-nodes/{nodeId}")
    public WalletRpcConfigService.RpcNodeView updateRpcNode(
            @PathVariable long id, @PathVariable long nodeId,
            @RequestBody WalletRpcConfigService.RpcNodeCommand body,
            HttpServletRequest request) {
        return rpc.updateRpcNode(principal(request), id, nodeId, body, ip(request));
    }

    /**
     * 删除 RPC 节点。
     */
    @DeleteMapping("/chains/{id}/rpc-nodes/{nodeId}")
    public Map<String, Object> deleteRpcNode(
            @PathVariable long id, @PathVariable long nodeId, HttpServletRequest request) {
        rpc.deleteRpcNode(principal(request), id, nodeId, ip(request));
        return Map.of("deleted", true, "id", nodeId);
    }

    /**
     * 测试 RPC 节点连通性。
     */
    @PostMapping("/chains/{id}/rpc-nodes/{nodeId}/test")
    public WalletRpcConfigService.RpcTestView testRpcNode(
            @PathVariable long id, @PathVariable long nodeId, HttpServletRequest request) {
        return rpc.testRpcNode(principal(request), id, nodeId);
    }

    /**
     * 查询 token 列表。
     */
    @GetMapping("/tokens")
    public List<WalletTokenConfigService.TokenView> tokens(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "") String chain,
            @RequestParam(defaultValue = "") String network,
            @RequestParam(defaultValue = "") String standard,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(required = false) Boolean effectiveEnabled,
            HttpServletRequest request) {
        return tokens.listTokens(principal(request), search, chain, network, standard,
                enabled, effectiveEnabled);
    }

    /**
     * 查询 token 矩阵（无分页）。
     */
    @GetMapping("/tokens/matrix")
    public List<WalletTokenConfigService.TokenView> tokenMatrix(
            @RequestParam(defaultValue = "") String search, HttpServletRequest request) {
        return tokens.listTokens(principal(request), search, "", "", "", null, null);
    }

    /**
     * 创建 token 配置。
     */
    @PostMapping("/tokens")
    public WalletTokenConfigService.TokenView createToken(
            @RequestBody WalletTokenConfigService.TokenCommand body,
            HttpServletRequest request) {
        return tokens.createToken(principal(request), body, ip(request));
    }

    /**
     * 更新 token 配置。
     */
    @PatchMapping("/tokens/{id}")
    public WalletTokenConfigService.TokenView updateToken(
            @PathVariable long id,
            @RequestBody WalletTokenConfigService.TokenCommand body,
            HttpServletRequest request) {
        return tokens.updateToken(principal(request), id, body, ip(request));
    }

    /**
     * 更新 token 状态。
     */
    @PatchMapping("/tokens/{id}/status")
    public WalletTokenConfigService.TokenView updateTokenStatus(
            @PathVariable long id,
            @RequestBody WalletTokenConfigService.TokenStatusCommand body,
            HttpServletRequest request) {
        return tokens.updateTokenStatus(principal(request), id, body, ip(request));
    }

    /**
     * 查询审计日志。
     */
    @GetMapping("/audit-log")
    public PageView<Map<String, Object>> auditLog(
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset,
            HttpServletRequest request) {
        return overview.auditLog(principal(request), limit, offset);
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
