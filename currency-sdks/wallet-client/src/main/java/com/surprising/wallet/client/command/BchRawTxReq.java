package com.surprising.wallet.client.command;

import lombok.Data;

import java.util.List;

/**
 * batch req
 *
 * @author lilaizhencn
 */
@Data
public class BchRawTxReq {
    private List<String> txids;
    private boolean verbose = true;
}
