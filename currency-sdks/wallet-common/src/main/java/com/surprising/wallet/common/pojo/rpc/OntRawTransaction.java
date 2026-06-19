package com.surprising.wallet.common.pojo.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * Created by lilaizhen on 2018/12/13.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntRawTransaction implements Serializable {
    private String Hash;
    private Long Nonce;
    private Long Height;
    private Long GasPrice;
    private Long GasLimit;

}
