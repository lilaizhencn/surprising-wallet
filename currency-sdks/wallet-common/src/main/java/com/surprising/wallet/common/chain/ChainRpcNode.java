package com.surprising.wallet.common.chain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
    private Boolean enabled;
    private Instant renewalDueAt;
    private String remark;
}
