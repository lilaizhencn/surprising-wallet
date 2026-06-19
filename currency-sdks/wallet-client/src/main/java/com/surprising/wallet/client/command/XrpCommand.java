package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONObject;
import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.surprising.wallet.common.annotation.RpcConfig;

/**
 * @author lilaizhen
 * @data 2018/6/11
 */

@RpcConfig(server = "${atomex.xrp.server}", needProxy = true)
public interface XrpCommand {
    @JsonRpcMethod(value = "account_info", required = true)
    JSONObject getAccountInfo(JSONObject param);

    @JsonRpcMethod(value = "ledger_current", required = true)
    JSONObject getCurrentHeight();

    @JsonRpcMethod(value = "tx", required = true)
    JSONObject getTransaction(JSONObject param);

    @JsonRpcMethod(value = "account_tx", required = true)
    JSONObject getAccountTx(JSONObject param);


    @JsonRpcMethod(value = "submit", required = true)
    JSONObject sendRawTransaction(JSONObject param);

    @JsonRpcMethod(value = "server_info", required = true)
    JSONObject getServerInfo();

}
