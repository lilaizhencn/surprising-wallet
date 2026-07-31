package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WithdrawRecord implements Serializable {

    /**
     * 保存 {@code id}，用于标识交易、区块或业务记录。
     */
    private Integer id;

    /**
     * 保存 {@code txId}，用于标识交易、区块或业务记录。
     */
    private String txId;

    /**
     * 保存 {@code address}，表示链、网络、资产或代币配置。
     */
    private String address;

    /**
     * 保存 {@code userId}，用于标识交易、区块或业务记录。
     */
    private Long userId;

    /**
     * 保存 {@code balance}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal balance;

    /**
     * 保存 {@code currency}，表示链、网络、资产或代币配置。
     */
    private Integer currency;

    /**
     * 保存 {@code biz}，用于承载当前对象的运行配置或业务数据。
     */
    private Integer biz;

    /**
     * 获取或查询 {@code getWithdrawId} 对应的数据，供调用方读取当前状态。
     */
    public String getWithdrawId() {
        return withdrawId;
    }

    /**
     * 设置或更新 {@code setWithdrawId} 对应的状态，并保持相关业务字段一致。
     */
    public void setWithdrawId(String withdrawId) {
        this.withdrawId = withdrawId;
    }

    /**
     * 系统间交互的唯一标识，防止发送重复交易
     */
    private String withdrawId;
    /**
     * 0:提现中;1:签名中;2:已发送; 3:已确认
     */
    private Byte status;

    /**
     * 保存 {@code createDate}，用于记录时间边界或审计时间。
     */
    private Date createDate;

    /**
     * 保存 {@code updateDate}，用于记录时间边界或审计时间。
     */
    private Date updateDate;

    /**
     * 保存 {@code fee}，用于保存金额、费用或链上执行状态。
     */
    private BigDecimal fee;

//    public static void main(final String[] args) {
//        final List<WithdrawRecord> recordList = new LinkedList<>();
//        final WithdrawRecord record1 = WithdrawRecord.builder()
//                .withdrawId("test")
//                .address("test1")
//                .build();
//        recordList.add(record1);
//        final WithdrawRecord record2 = WithdrawRecord.builder()
//                .withdrawId("test")
//                .address("test2")
//                .build();
//        recordList.add(record2);
//        final Set<WithdrawRecord> recordSet = recordList.parallelStream().distinct().collect(Collectors.toSet());
//        System.out.println(recordSet.size());
//    }

    /**
     * 比较当前对象与目标对象是否具有相同的业务含义。
     */
    @Override
    public boolean equals(final Object o) {
        if (o == this) {
            return true;
        } else if (!(o instanceof WithdrawRecord)) {
            return false;
        } else {
            final WithdrawRecord other = (WithdrawRecord) o;
            if (!other.canEqual(this)) {
                return false;
            } else {
                if (this.getWithdrawId().equals(other.getWithdrawId())) {
                    return true;
                }
                return false;
            }
        }
    }

    /**
     * 根据对象的业务字段计算哈希值，保证与相等性判断一致。
     */
    @Override
    public int hashCode() {
        int result = 1;
        result = result * 59 + (this.withdrawId == null ? 43 : this.withdrawId.hashCode());
        return result;
    }

    /**
     * 判断 {@code canEqual} 对应的条件是否成立，并返回明确的布尔结果。
     */
    protected boolean canEqual(final Object other) {
        return other instanceof WithdrawRecord;
    }
}