package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
/**
 * 链 RPC 节点配置，记录各区块链网络 RPC 节点的连接参数和认证信息。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code chain} / {@code network} / {@code environment} - 链、网络和环境标识</li>
 *   <li>{@code nodeLabel} - 节点标签</li>
 *   <li>{@code purpose} - 节点用途（如扫描、广播等）</li>
 *   <li>{@code connectionType} - 连接类型（HTTP/WebSocket 等）</li>
 *   <li>{@code rpcUrl} - RPC 端点地址</li>
 *   <li>{@code authType} / {@code authHeaderName} / {@code apiKey} / {@code username} / {@code password} - 认证配置</li>
 *   <li>{@code priority} - 优先级（数值越小越优先）</li>
 *   <li>{@code minRequestIntervalMs} - 最小请求间隔（毫秒），用于限流</li>
 *   <li>{@code enabled} - 是否启用</li>
 *   <li>{@code renewalDueAt} - 密钥轮换到期时间</li>
 * </ul>
 */
@AllArgsConstructor
public class ChainRpcNode {
    private Long id;
    private String chain;
    private String network;
    private String environment;
    private String nodeLabel;
    private String purpose;
    private String connectionType;
    private String rpcUrl;
    private String authType;
    private String authHeaderName;
    private String apiKey;
    private String apiKeyRef;
    private String username;
    private String usernameRef;
    private String password;
    private String passwordRef;
    private Integer priority;
    private Integer minRequestIntervalMs;
    private Boolean enabled;
    private Instant renewalDueAt;
    private String remark;
}
