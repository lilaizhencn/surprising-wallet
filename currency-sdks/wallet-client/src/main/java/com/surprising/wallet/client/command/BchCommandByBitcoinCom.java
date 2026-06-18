package com.surprising.wallet.client.command;

import com.surprising.wallet.common.annotation.RpcConfig;
import com.surprising.wallet.common.pojo.rpc.BtcLikeBlock;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
import retrofit2.http.Body;
import retrofit2.http.GET;
import retrofit2.http.Path;

import java.util.List;

import static com.surprising.wallet.common.annotation.RpcConfig.RpcType.REST_RPC;

/**
 * bch官网rest api
 * https://rest.bitcoin.com/#
 */
@RpcConfig(
        server = "${atomex.bch.bitcoin.com.server}",
        type = REST_RPC
)
public interface BchCommandByBitcoinCom {
    /**
     * 获取区块链当前高度
     * curl -X GET "https://rest.bitcoin.com/v2/blockchain/getBlockCount" -H "accept: application/json"
     *
     * @return height
     */
    @GET(value = "/blockchain/getBlockCount")
    long getBlockCount();

    /**
     * 根据hash获取区块详情
     * curl -X GET "https://rest.bitcoin.com/v2/block/detailsByHeight/500000" -H "accept: application/json"
     *
     * @param height 高度
     */
    @GET(value = "/block/detailsByHeight/{height}")
    BtcLikeBlock getBlock(@Path("height") long height);

    /**
     * getrawtransaction 获取单个交易详情
     * curl -X GET "https://rest.bitcoin.com/v2/rawtransactions/getRawTransaction/fe28050b93faea61fa88c4c630f0e1f0a1c24d0082dd0e10d369e13212128f33?verbose=true" -H "accept: application/json"
     *
     * @param txid    交易id
     * @param verbose 获取更多详情
     */
    @GET(value = "/rawtransactions/getRawTransaction/{txid}?verbose={verbose}")
    BtcLikeRawTransaction getRawTransaction(@Path("txid") String txid, @Path("verbose") boolean verbose);

    /**
     * 获取多个交易详情
     *
     * @param req request body
     * @return List<BtcLikeRawTransaction>
     */
    @GET(value = "/rawtransactions/getrawtransaction")
    List<BtcLikeRawTransaction> getRawTransactionList(@Body BchRawTxReq req);

    /**
     * getrawtransaction 获取单个交易详情
     * curl -X GET "https://rest.bitcoin.com/v2/transaction/details/fe28050b93faea61fa88c4c630f0e1f0a1c24d0082dd0e10d369e13212128f33" -H "accept: application/json"
     *
     * @param txid 交易id
     */
    @GET(value = "/transaction/details/{txid}")
    BtcLikeRawTransaction getTransactionDetail(String txid);

    /**
     * 获取多个交易详情
     *
     * @param req request body
     * @return List<BtcLikeRawTransaction>
     */
    @GET(value = "/transaction/details")
    List<BtcLikeRawTransaction> getTransactionDetailList(@Body BchTxDetailReq req);

    /**
     * 解析原始交易
     *
     * @param txHex
     * @return
     */
    @GET(value = "/rawtransactions/decodeRawTransaction/{hex}")
    BtcLikeRawTransaction decodeRawTransactionStr(@Path("hex") String txHex);

    /**
     * 解析原始交易
     *
     * @param txHex
     * @return
     */
    @GET(value = "/rawtransactions/decodeRawTransaction/{txHex}")
    String decodeRawTransactionToString(@Path("txHex") String txHex);

    /**
     * 发送原始交易
     *
     * @param hex
     * @return
     */
    @GET(value = "/rawtransactions/sendRawTransaction/{hex}")
    String sendRawTransaction(@Path("hex") String hex);
}
