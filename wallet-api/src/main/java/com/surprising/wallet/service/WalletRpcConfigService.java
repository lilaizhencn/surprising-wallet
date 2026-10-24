package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.config.WalletRpcPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.Locale;

import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.repository.CustodyAuditRepository;
import com.surprising.wallet.repository.TokenConfigRepository;
import static com.surprising.wallet.service.WalletConfigSupport.*;

/** RPC 节点凭据、探测及必需用途约束。 */
@Service
public class WalletRpcConfigService {
    private final ChainProfileRepository chainProfileRepository;
    private final ChainRpcNodeRepository rpcNodeRepository;
    private final TokenConfigRepository tokenConfigRepository;
    private final CustodyAuditRepository audits;
    private final ObjectMapper mapper;
    private final String environment;
    private final WalletRpcClient rpcClient;

    public WalletRpcConfigService(ChainProfileRepository chainProfileRepository,
                                 ChainRpcNodeRepository rpcNodeRepository,
                                 TokenConfigRepository tokenConfigRepository,
                                 CustodyAuditRepository audits, ObjectMapper mapper,
                                 @Value("${sw.app.env.name:dev}") String environment,
                                 WalletRpcClient rpcClient) {
        this.chainProfileRepository = chainProfileRepository;
        this.rpcNodeRepository = rpcNodeRepository;
        this.tokenConfigRepository = tokenConfigRepository;
        this.audits = audits;
        this.mapper = mapper;
        this.environment = normalizeLower(environment);
        this.rpcClient = rpcClient;
    }

    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public List<RpcNodeView> listRpcNodes(CustodyPrincipal actor, long chainId) {
        requirePlatformAdmin(actor);
        return listForProfile(requireChain(chainId));
    }

    @Transactional
    public RpcNodeView createRpcNode(CustodyPrincipal actor, long chainId,
                                     RpcNodeCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        AccountChainProfile chain = requireChain(chainId);
        ChainRpcNode values = mergeRpc(null, command);
        validateRpc(values);
        long id = rpcNodeRepository.insert(
                chain.getChain(), chain.getNetwork(), values.getEnvironment(), values.getNodeLabel(), values.getPurpose(),
                values.getConnectionType(), values.getRpcUrl(), values.getAuthType(), values.getAuthHeaderName(),
                values.getApiKey(), values.getUsername(), values.getPassword(), values.getPriority(),
                values.getMinRequestIntervalMs(), values.getEnabled(), values.getRenewalDueAt(), values.getRemark());
        audit(actor, "WALLET_RPC.CREATE", "CHAIN_RPC_NODE", String.valueOf(id), sourceIp,
                rpcAudit(values));
        return rpcView(requireRpcNode(chain, id));
    }

    @Transactional
    public RpcNodeView updateRpcNode(CustodyPrincipal actor, long chainId, long nodeId,
                                     RpcNodeCommand command, String sourceIp) {
        requirePlatformAdmin(actor);
        AccountChainProfile chain = requireChain(chainId);
        ChainRpcNode current = requireRpcNode(chain, nodeId);
        ChainRpcNode values = mergeRpc(current, command);
        validateRpc(values);
        if (current.getEnabled() && (!values.getEnabled()
                || !current.getPurpose().equalsIgnoreCase(values.getPurpose())
                || !current.getEnvironment().equalsIgnoreCase(values.getEnvironment()))) {
            validateCanDisableRpc(chain, nodeId, current.getPurpose(), current.getEnvironment());
        }
        rpcNodeRepository.update(nodeId, chain.getChain(), chain.getNetwork(), values.getEnvironment(),
                values.getNodeLabel(), values.getPurpose(), values.getConnectionType(), values.getRpcUrl(),
                values.getAuthType(), values.getAuthHeaderName(), values.getApiKey(), values.getUsername(),
                values.getPassword(), values.getPriority(), values.getMinRequestIntervalMs(), values.getEnabled(),
                values.getRenewalDueAt(), values.getRemark());
        audit(actor, "WALLET_RPC.UPDATE", "CHAIN_RPC_NODE", String.valueOf(nodeId), sourceIp,
                rpcAudit(values));
        return rpcView(requireRpcNode(chain, nodeId));
    }

    @Transactional
    public void deleteRpcNode(CustodyPrincipal actor, long chainId, long nodeId, String sourceIp) {
        requirePlatformAdmin(actor);
        AccountChainProfile chain = requireChain(chainId);
        ChainRpcNode node = requireRpcNode(chain, nodeId);
        if (node.getEnabled()) {
            throw new IllegalStateException("disable the RPC node before deleting it");
        }
        rpcNodeRepository.delete(nodeId, chain.getChain(), chain.getNetwork());
        audit(actor, "WALLET_RPC.DELETE", "CHAIN_RPC_NODE", String.valueOf(nodeId), sourceIp,
                Map.of("chain", chain.getChain(), "network", chain.getNetwork()));
    }

    public RpcTestView testRpcNode(CustodyPrincipal actor, long chainId, long nodeId) {
        requirePlatformAdmin(actor);
        AccountChainProfile chain = requireChain(chainId);
        ChainRpcNode node = requireRpcNode(chain, nodeId);
        WalletRpcClient.ProbeResult result = rpcClient.probe(node);
        rpcNodeRepository.updateProbe(nodeId, result.checkedAt(), result.latencyMs(),
                result.statusCode(), result.error());
        return new RpcTestView(result.success(), result.statusCode(), result.latencyMs(),
                result.error(), result.checkedAt());
    }

    List<RpcNodeView> listForProfile(AccountChainProfile profile) {
        return rpcNodeRepository.listByChain(profile.getChain(), profile.getNetwork()).stream()
                .map(this::rpcView).toList();
    }

    private AccountChainProfile requireChain(long id) {
        return chainProfileRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("chain profile not found"));
    }

    private ChainRpcNode requireRpcNode(AccountChainProfile chain, long nodeId) {
        return rpcNodeRepository.findById(nodeId, chain.getChain(), chain.getNetwork())
                .orElseThrow(() -> new IllegalArgumentException("RPC node not found for chain profile"));
    }

    private void validateCanDisableRpc(AccountChainProfile chain, long nodeId, String purpose, String nodeEnvironment) {
        if (!chain.getEnabled() || !WalletRpcPolicy.requiredPurposes(
                chain.getChain(), chain.getNetwork(), tokenConfigRepository.existsByChainAndNetwork(chain.getChain(), chain.getNetwork())).contains(normalizeLower(purpose))
                || !environment.equalsIgnoreCase(nodeEnvironment)) {
            return;
        }
        long alternatives = rpcNodeRepository.countEnabledAlternatives(
                chain.getChain(), chain.getNetwork(), environment, purpose, nodeId);
        if (alternatives == 0) {
            throw new IllegalStateException("an enabled chain must retain an enabled RPC node for required purpose " + purpose);
        }
    }

    private void validateRpc(ChainRpcNode value) {
        required(value.getEnvironment(), "environment");
        required(value.getNodeLabel(), "nodeLabel");
        required(value.getPurpose(), "purpose");
        required(value.getConnectionType(), "connectionType");
        required(value.getRpcUrl(), "rpcUrl");
        try {
            URI uri = URI.create(value.getRpcUrl());
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!List.of("http", "https", "ws", "wss").contains(scheme)
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IllegalArgumentException("rpcUrl must be an absolute HTTP or WebSocket URL");
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("rpcUrl must be an absolute HTTP or WebSocket URL");
        }
        if (WalletRpcPolicy.containsPlaceholder(value.getRpcUrl())
                || WalletRpcPolicy.containsPlaceholder(value.getApiKey())
                || WalletRpcPolicy.containsPlaceholder(value.getUsername())
                || WalletRpcPolicy.containsPlaceholder(value.getPassword())) {
            throw new IllegalArgumentException("RPC URL and credentials cannot contain placeholders");
        }
        if (value.getEnabled() && WalletRpcPolicy.requiresApiKey(value.getAuthType(), value.getConnectionType())
                && blank(value.getApiKey())) {
            throw new IllegalArgumentException("API key is required by the selected RPC authentication");
        }
        if (value.getEnabled() && WalletRpcPolicy.requiresUsernamePassword(value.getAuthType())
                && (blank(value.getUsername()) || blank(value.getPassword()))) {
            throw new IllegalArgumentException("username and password are required by the selected RPC authentication");
        }
        if (value.getPriority() < 0 || value.getMinRequestIntervalMs() < 0) {
            throw new IllegalArgumentException("RPC priority and request interval must not be negative");
        }
    }

    private ChainRpcNode mergeRpc(ChainRpcNode current, RpcNodeCommand command) {
        if (command == null) throw new IllegalArgumentException("RPC body is required");
        String authType = upper(or(command.authType(), current == null ? "NONE" : current.getAuthType()));
        String connectionType = upper(or(command.connectionType(), current == null ? "HTTP_JSON_RPC" : current.getConnectionType()));
        String apiKey = secret(command.apiKey(), current == null ? null : current.getApiKey());
        String username = secret(command.username(), current == null ? null : current.getUsername());
        String password = secret(command.password(), current == null ? null : current.getPassword());
        if ("NONE".equals(authType) && !"BLOCKFROST".equals(connectionType)) {
            apiKey = null;
            username = null;
            password = null;
        } else if (WalletRpcPolicy.requiresApiKey(authType, connectionType)) {
            username = null;
            password = null;
        } else if (WalletRpcPolicy.requiresUsernamePassword(authType)) {
            apiKey = null;
        }
        return ChainRpcNode.builder()
                .environment(lower(or(command.environment(), current == null ? environment : current.getEnvironment())))
                .nodeLabel(or(command.nodeLabel(), current == null ? null : current.getNodeLabel()))
                .purpose(lower(or(command.purpose(), current == null ? "rpc" : current.getPurpose())))
                .connectionType(connectionType).rpcUrl(or(command.rpcUrl(), current == null ? null : current.getRpcUrl()))
                .authType(authType).authHeaderName(or(command.authHeaderName(), current == null ? null : current.getAuthHeaderName()))
                .apiKey(apiKey).username(username).password(password)
                .priority(or(command.priority(), current == null ? null : current.getPriority(), 100))
                .minRequestIntervalMs(or(command.minRequestIntervalMs(), current == null ? null : current.getMinRequestIntervalMs(), 0))
                .enabled(or(command.enabled(), current == null ? null : current.getEnabled(), false))
                .renewalDueAt(or(command.renewalDueAt(), current == null ? null : current.getRenewalDueAt()))
                .remark(or(command.remark(), current == null ? null : current.getRemark())).build();
    }

    private RpcNodeView rpcView(ChainRpcNode row) {
        return new RpcNodeView(row.getId(), row.getEnvironment(), row.getNodeLabel(), row.getPurpose(),
                row.getConnectionType(), row.getRpcUrl(), row.getAuthType(), row.getAuthHeaderName(),
                !blank(row.getApiKey()), !blank(row.getUsername()), !blank(row.getPassword()),
                intValue(row.getPriority()), intValue(row.getMinRequestIntervalMs()), Boolean.TRUE.equals(row.getEnabled()),
                row.getRenewalDueAt(), row.getRemark(), row.getLastCheckedAt(), row.getLastLatencyMs(),
                row.getLastHttpStatus(), row.getLastError(), row.getCreatedAt(), row.getUpdatedAt());
    }

    private void audit(CustodyPrincipal actor, String action, String resourceType,
                       String resourceId, String sourceIp, Map<String, ?> details) {
        audits.insert(UUID.randomUUID(), null, "PLATFORM_USER", actor.actorId().toString(), action,
                resourceType, resourceId, sourceIp, mapper.writeValueAsString(details));
    }

    private static Map<String, Object> rpcAudit(ChainRpcNode value) {
        return Map.of("environment", value.getEnvironment(), "purpose", value.getPurpose(),
                "enabled", value.getEnabled(), "credentialsConfigured",
                !blank(value.getApiKey()) || !blank(value.getUsername()) || !blank(value.getPassword()));
    }

    private static String secret(String requested, String current) {
        return requested == null || requested.isBlank() ? current : requested;
    }

    public record RpcNodeCommand(String environment, String nodeLabel, String purpose,
                                 String connectionType, String rpcUrl, String authType,
                                 String authHeaderName, String apiKey, String username, String password,
                                 Integer priority, Integer minRequestIntervalMs, Boolean enabled,
                                 Instant renewalDueAt, String remark) {}

    public record RpcNodeView(long id, String environment, String nodeLabel, String purpose,
                              String connectionType, String rpcUrl, String authType,
                              String authHeaderName, boolean apiKeyConfigured,
                              boolean usernameConfigured, boolean passwordConfigured,
                              int priority, int minRequestIntervalMs, boolean enabled,
                              Instant renewalDueAt, String remark, Instant lastCheckedAt,
                              Long lastLatencyMs, Integer lastHttpStatus, String lastError,
                              Instant createdAt, Instant updatedAt) {}

    public record RpcTestView(boolean success, Integer statusCode, long latencyMs,
                              String error, Instant checkedAt) {}

    void requireEnabledNodes(String chain, String network, boolean hasTokens) {
        for (String purpose : WalletRpcPolicy.requiredPurposes(chain, network, hasTokens)) {
            List<ChainRpcNode> nodes = rpcNodeRepository.listEnabledForPurpose(chain, network, environment, purpose);
            if (nodes.isEmpty()) {
                throw new IllegalStateException("configure an enabled " + purpose
                        + " RPC node for environment " + environment + " before enabling this chain");
            }
            nodes.forEach(this::validateRpc);
        }
    }

}
