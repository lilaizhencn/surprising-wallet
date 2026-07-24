package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
/**
 * 提现订单实体，记录用户发起的提现请求的完整信息。
 *
 * <p>订单状态（{@code status}）：</p>
 * <ul>
 *   <li>0 - 待签名</li>
 *   <li>1 - 已广播</li>
 *   <li>2 - 已确认</li>
 *   <li>3 - 失败</li>
 * </ul>
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code userId} - 用户 ID</li>
 *   <li>{@code currency} - 币种</li>
 *   <li>{@code chain} - 目标链标识</li>
 *   <li>{@code fromAddress} / {@code toAddress} - 发送方/接收方地址</li>
 *   <li>{@code amount} - 提现金额</li>
 *   <li>{@code fee} - 手续费</li>
 *   <li>{@code txId} - 交易哈希（广播后填充）</li>
 *   <li>{@code signatureData} - 签名数据 JSON（utxos、addresses 等）</li>
 * </ul>
 */
public class WithdrawOrder implements Serializable {
    private Long id;
    private Long userId;
    private Integer currency;
    private String chain;
    private String fromAddress;
    private String toAddress;
    private BigDecimal amount;
    private BigDecimal fee;
    private Integer status;       // 0:待签名 1:已广播 2:已确认 3:失败
    private String txId;
    private String signatureData; // JSON payload for signing (utxos, addresses, etc.)
    private String remark;
    private Date createDate;
    private Date updateDate;
}
