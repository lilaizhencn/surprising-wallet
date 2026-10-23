package com.surprising.wallet.common.queue;

import com.surprising.wallet.common.chain.UtxoOutput;
import java.util.UUID;

/** 外部业务可消费的租户充值观察通知；实际入账由托管事件和账本确认。 */
public record UtxoDepositNotification(UUID tenantId, String chain, String network,
                                      String assetSymbol, UtxoOutput output) { }
