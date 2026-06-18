package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONObject;
import com.surprising.wallet.common.annotation.RpcConfig;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

import static com.surprising.wallet.common.annotation.RpcConfig.RpcType.REST_RPC;

/**
 * @author lilaizhen
 * @data 28/03/2018
 */
@RpcConfig(
        server = "${atomex.xem.server}",
        type = REST_RPC
)
public interface XemCommand {

    /**
     * 获得最高高度
     *
     * @return
     */
    @GET("/chain/height")
    JSONObject getBlockCount();

    /**
     * 获得余额
     *
     * @return
     */
    @GET("/account/get")
    JSONObject getBalance(@Query("address") String address);


    /**
     * 获得tx
     *
     * @return
     */
    @GET("/transaction/get")
    JSONObject getTransaction(@Query("hash") String hash);

    /**
     * 发送原始交易
     *
     * @param param
     * @return
     */
    @POST("/transaction/announce")
    JSONObject sendRawTransaction(@Body JSONObject param);

    /**
     * @param param
     * @return
     */
    @POST("/local/chain/blocks-after")
    JSONObject blocksByHeight(@Body JSONObject param);


    /**
     * 获得余额
     *
     * @return
     */
    @GET("/account/mosaic/owned")
    JSONObject getAcscountMosaics(@Query("address") String address);
}
