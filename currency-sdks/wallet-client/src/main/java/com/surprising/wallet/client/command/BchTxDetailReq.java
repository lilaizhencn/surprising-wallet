package com.surprising.wallet.client.command;

import lombok.Data;

import java.util.List;

/**
 * batch req
 *
 * @author lilaizhencn
 */
@Data
public class BchTxDetailReq {
    private List<String> txids;
}
