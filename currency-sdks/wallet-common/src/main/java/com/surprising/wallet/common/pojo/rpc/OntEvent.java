package com.surprising.wallet.common.pojo.rpc;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;

/**
 * Created by lilaizhen on 2018/12/13.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OntEvent implements Serializable {
    int GasConsumed;


    String TxHash;

    int State;

    OntStates[] Notify;

}
