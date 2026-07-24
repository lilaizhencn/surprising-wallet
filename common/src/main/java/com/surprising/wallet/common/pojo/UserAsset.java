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
 * 用户资产实体，记录用户在特定币种下的账户余额和冻结金额。
 *
 * <p>核心字段：</p>
 * <ul>
 *   <li>{@code userId} - 用户 ID</li>
 *   <li>{@code currency} - 币种标识</li>
 *   <li>{@code balance} - 可用余额</li>
 *   <li>{@code frozen} - 冻结金额</li>
 *   <li>{@code version} - 乐观锁版本号</li>
 * </ul>
 */
public class UserAsset implements Serializable {
    private Long id;
    private Long userId;
    private Integer currency;
    private BigDecimal balance;
    private BigDecimal frozen;
    private Integer version;
    private Date createDate;
    private Date updateDate;
}
