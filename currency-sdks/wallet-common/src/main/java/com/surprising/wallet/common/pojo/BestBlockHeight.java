package com.surprising.wallet.common.pojo;

import lombok.*;

import java.io.Serializable;
import java.util.Date;

/**
 * @author lilaizhen
 * @date 2018-05-02
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class BestBlockHeight implements Serializable {
    /**
     *
     */
    private Integer id;
    /**
     *
     */
    private Integer currency;
    /**
     *
     */
    private Long height;
    /**
     * 区块更新间隔时间，默认5分钟
     */
    private Long intervalTime;
    /**
     *
     */
    private Date createDate;
    /**
     *
     */
    private Date updateDate;
}