package com.surprising.wallet.client.command;

import com.alibaba.fastjson.JSONObject;
import retrofit2.http.GET;
import retrofit2.http.Path;
import retrofit2.http.Query;

/**
 * @author lilaizhen
 */
public interface BlockChainBtcCommand {

    /**
     * 获得最高高度
     * {
     * "hash": "000000000000000000027c3e6eada9a7969645084a0b241e1977b76cf0b2bf0c",
     * "time": 1595043545,
     * "block_index": 0,
     * "height": 639703,
     * "txIndexes": []
     * }
     */
    @GET("/latestblock")
    JSONObject getBlockCount(@Query("key") String key);

    /**
     * 获得余额
     *
     * @param active 支持多个地址用"|" 分割 如果那样的话 需要自己补充一个中括号。所以还是别传多个了。
     *               {
     *               "1MDUoxL1bGvMxhuoDYx6i11ePytECAk9QK": {
     *               "final_balance": 0,
     *               "n_tx": 0,
     *               "total_received": 0
     *               },
     *               "15EW3AMRm2yP6LEF5YKKLYwvphy3DmMqN6": {
     *               "final_balance": 0,
     *               "n_tx": 2,
     *               "total_received": 310630609
     *               }
     *               }
     */
    @GET("/balance")
    JSONObject getBalance(@Query("active") String active, @Query("key") String key);

    /**
     * 根据hash获取区块详情 此接口如果不用hex 返回的数据大约是11M 。 hex返回的大约是 2.6M
     * https://blockchain.info/rawblock/$block_hash
     * You can also request the block to return in binary form (Hex encoded) using ?format=hex
     *
     * @param hash
     */
    @GET("/rawblock/{hash}?format=hex&cors=true")
    String getHexBlockByHash(@Path("hash") String hash, @Query("key") String key);

    /**
     * 根据hash获取区块详情 此接口如果不用hex 返回的数据大约是11M 。 hex返回的大约是 2.6M
     * https://blockchain.info/rawblock/$block_hash
     * You can also request the block to return in binary form (Hex encoded) using ?format=hex
     *
     * @param hash
     */
    @GET("/rawblock/{hash}?format=json&cors=true")
    String getBlockByHash(@Path("hash") String hash, @Query("key") String key);

    /**
     * 根据块高度获取区块详情  不支持hex
     * 包含所有交易数据 和块的元数据
     *
     * @param height
     */
    @GET("/block-height/{height}?format=json&cors=true")
    String getBlockByHeight(@Path("height") long height, @Query("key") String key);

    /**
     * https://blockchain.info/rawtx/$tx_hash
     * getTxByTxId 建议使用 getHexTxByTxId 压缩的效率更高一些
     *
     * @param txId 交易id
     */
    @GET("/rawtx/{txId}?format=json")
    String getTxByTxId(@Path("txId") String txId, @Query("key") String key);

    /**
     * https://blockchain.info/rawtx/$tx_hash
     * getrawtransaction
     * You can also request the transaction to return in binary form (Hex encoded) using ?format=hex
     *
     * @param txId 交易id
     */
    @GET("/rawtx/{txId}?format=hex")
    String getHexTxByTxId(@Path("txId") String txId, @Query("key") String key);
}
