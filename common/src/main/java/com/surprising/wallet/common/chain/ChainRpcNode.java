package com.surprising.wallet.common.chain;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 链 RPC 节点配置；连接认证、调度和探测元数据由同一行模型承载。 */
@Data
@Builder
@NoArgsConstructor
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
    private Instant lastCheckedAt;
    private Long lastLatencyMs;
    private Integer lastHttpStatus;
    private String lastError;
    private Instant createdAt;
    private Instant updatedAt;
}
