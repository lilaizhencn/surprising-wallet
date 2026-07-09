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
 * @author lilaizhen
 * @date 2018-04-02
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class WithdrawRecord implements Serializable {
    /**
     *
     */
    private Integer id;
    /**
     *
     */
    private String txId;
    /**
     *
     */
    private String address;
    /**
     *
     */
    private Long userId;
    /**
     *
     */
    private BigDecimal balance;
    /**
     *
     */
    private Integer currency;
    /**
     *
     */
    private Integer biz;

    public String getWithdrawId() {
        return withdrawId;
    }

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
     *
     */
    private Date createDate;
    /**
     *
     */
    private Date updateDate;
    /**
     *
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

    @Override
    public int hashCode() {
        int result = 1;
        result = result * 59 + (this.withdrawId == null ? 43 : this.withdrawId.hashCode());
        return result;
    }

    protected boolean canEqual(final Object other) {
        return other instanceof WithdrawRecord;
    }
}