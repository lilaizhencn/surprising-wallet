package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 该类型封装所在链或钱包模块的配置、业务状态和校验逻辑。
 */
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
    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Long id;
    /**
     * 保存 {@code chain}，表示链、网络、资产或代币配置。
     */
    private String chain;
    /**
     * 保存 {@code network}，表示链、网络、资产或代币配置。
     */
    private String network;
    /**
     * 保存 {@code environment}，用于承载当前对象的运行配置或业务数据。
     */
    private String environment;
    /**
     * 保存 {@code nodeLabel}，用于承载当前对象的运行配置或业务数据。
     */
    private String nodeLabel;
    /**
     * 保存 {@code purpose}，用于承载当前对象的运行配置或业务数据。
     */
    private String purpose;
    /**
     * 保存 {@code connectionType}，用于承载当前对象的运行配置或业务数据。
     */
    private String connectionType;
    /**
     * 保存 {@code rpcUrl}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private String rpcUrl;
    /**
     * 保存 {@code authType}，用于保存签名、认证或密钥相关材料。
     */
    private String authType;
    /**
     * 保存 {@code authHeaderName}，用于保存签名、认证或密钥相关材料。
     */
    private String authHeaderName;
    /**
     * 保存 {@code apiKey}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private String apiKey;
    /**
     * 保存 {@code apiKeyRef}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private String apiKeyRef;
    /**
     * 保存 {@code username}，用于承载当前对象的运行配置或业务数据。
     */
    private String username;
    /**
     * 保存 {@code usernameRef}，用于承载当前对象的运行配置或业务数据。
     */
    private String usernameRef;
    /**
     * 保存 {@code password}，用于保存签名、认证或密钥相关材料。
     */
    private String password;
    /**
     * 保存 {@code passwordRef}，用于保存签名、认证或密钥相关材料。
     */
    private String passwordRef;
    /**
     * 保存 {@code priority}，用于承载当前对象的运行配置或业务数据。
     */
    private Integer priority;
    /**
     * 保存 {@code minRequestIntervalMs}，用于承载当前对象的运行配置或业务数据。
     */
    private Integer minRequestIntervalMs;
    /**
     * 保存 {@code enabled}，记录开关、处理状态、确认结果或重试信息。
     */
    private Boolean enabled;
    /**
     * 保存 {@code renewalDueAt}，用于承载当前对象的运行配置或业务数据。
     */
    private Instant renewalDueAt;
    /**
     * 保存 {@code remark}，用于承载当前对象的运行配置或业务数据。
     */
    private String remark;
}
