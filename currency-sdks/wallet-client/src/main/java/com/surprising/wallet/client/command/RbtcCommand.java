package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.surprising.wallet.common.annotation.RpcConfig;

/**
 * @author lilaizhen
 * @data 12/04/2018
 */
@RpcConfig(server = "${atomex.rbtc.server}")
public interface RbtcCommand extends EthLikeCommand {
    @JsonRpcMethod("eth_call")
    String getTokenBalance(JSONObject param, String latest);
}
