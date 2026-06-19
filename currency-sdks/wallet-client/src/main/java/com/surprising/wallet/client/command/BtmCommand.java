package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.annotation.RpcConfig;
import retrofit2.http.Body;
import retrofit2.http.POST;

import static com.surprising.wallet.common.annotation.RpcConfig.RpcType.REST_RPC;

/**
 * @author lilaizhen
 * @data 28/03/2018
 */
@RpcConfig(
        server = "${atomex.btm.server}",
        type = REST_RPC
)
public interface BtmCommand {

    /**
     * 获得chain的最新信息
     *
     * @return
     */
    @POST("/get-block")
    JSONObject getBlock(@Body JSONObject param);

    /**
     * 获得chain的最新信息
     *
     * @return
     */
    @POST("/get-block-count")
    JSONObject getBlockCount();


    /**
     * 获得chain的最新信息
     *
     * @return
     */
    @POST("/get-transaction")
    JSONObject getTransaction(@Body JSONObject param);

    /**
     * 发送原始交易
     *
     * @param param
     * @return
     */
    @POST("/submit-transaction")
    JSONObject sendRawTransaction(@Body JSONObject param);
}
