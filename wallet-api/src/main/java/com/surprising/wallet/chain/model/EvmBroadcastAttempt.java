package com.surprising.wallet.chain.model;

import java.util.UUID;

/** 已签名尝试的重播身份；明文交易只在工作流执行广播时解密。 */
public record EvmBroadcastAttempt(UUID tenantId, UUID batchId, String txHash,
                                  String signedTxCiphertext, int rebroadcastCount) { }
