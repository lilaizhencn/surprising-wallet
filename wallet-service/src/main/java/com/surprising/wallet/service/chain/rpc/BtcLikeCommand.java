package com.surprising.wallet.service.chain.rpc;

import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.surprising.wallet.common.pojo.rpc.BtcLikeBlock;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
public interface BtcLikeCommand {
    @JsonRpcMethod(value = "getblockcount", required = true)
    long getBlockCount();

    @JsonRpcMethod(value = "getblockhash", required = true)
    String getBlockHash(long height);

    @JsonRpcMethod(value = "getblock", required = true)
    BtcLikeBlock getBlock(String hash);

    @JsonRpcMethod(value = "getrawtransaction", required = true)
    BtcLikeRawTransaction getRawTransaction(String txid, boolean verbose);

    @JsonRpcMethod(value = "getrawtransaction", required = true)
    BtcLikeRawTransaction getRawTransaction(String txid, int verbose);

    @JsonRpcMethod(value = "getrawtransaction", required = true)
    String getRawTransactionStr(String txid);

    @JsonRpcMethod(value = "decoderawtransaction", required = true)
    BtcLikeRawTransaction decodeRawTransactionStr(String txHex);

    @JsonRpcMethod(value = "decoderawtransaction", required = true)
    String decodeRawTransactionToString(String txHex);

    @JsonRpcMethod(value = "sendrawtransaction", required = true)
    String sendRawTransaction(String hex);
}
