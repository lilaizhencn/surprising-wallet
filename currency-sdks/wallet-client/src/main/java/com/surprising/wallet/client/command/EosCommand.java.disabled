package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;
import retrofit2.http.GET;

import static com.surprising.wallet.common.annotation.RpcConfig.RpcType.REST_RPC;

/**
 * @author lilaizhen
 * @data 28/03/2018
 */
@RpcConfig(
        server = "${atomex.eos.server}",
        type = REST_RPC
)
public interface EosCommand {

    /**
     * 获得chain的最新信息
     *
     * @return
     */
    @GET("/v1/chain/get_info")
    String getChainInfo();
}
