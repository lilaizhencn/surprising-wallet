package com.surprising.wallet.chain.rpc;

import com.googlecode.jsonrpc4j.JsonRpcMethod;
import com.surprising.wallet.common.pojo.rpc.BtcLikeBlock;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
/**
 * BTC-like 链的 JSON-RPC 通用命令接口（BTC/BCH/LTC/DOGE）。
 *
 * <p>定义与 bitcoind 兼容的 JSON-RPC 方法映射，包括区块查询、交易查询/解码/广播。
 * 子接口（如 {@link BtcCommand}、{@link BchCommand}）继承此接口以声明对应链的 RPC 命令。
 */
public interface BtcLikeCommand {

    /** 获取当前区块链的最新区块高度 */
    @JsonRpcMethod(value = "getblockcount", required = true)
    long getBlockCount();

    /** 通过高度获取区块哈希 */
    @JsonRpcMethod(value = "getblockhash", required = true)
    String getBlockHash(long height);

    /** 通过哈希获取完整区块数据 */
    @JsonRpcMethod(value = "getblock", required = true)
    BtcLikeBlock getBlock(String hash);

    /** 通过 txid 获取原始交易（verbose 模式返回结构化数据） */
    @JsonRpcMethod(value = "getrawtransaction", required = true)
    BtcLikeRawTransaction getRawTransaction(String txid, boolean verbose);

    /** 通过 txid 获取原始交易（int 版 verbose） */
    @JsonRpcMethod(value = "getrawtransaction", required = true)
    BtcLikeRawTransaction getRawTransaction(String txid, int verbose);

    /** 通过 txid 获取原始交易字符串（非 verbose） */
    @JsonRpcMethod(value = "getrawtransaction", required = true)
    String getRawTransactionStr(String txid);

    /** 解码十六进制原始交易 */
    @JsonRpcMethod(value = "decoderawtransaction", required = true)
    BtcLikeRawTransaction decodeRawTransactionStr(String txHex);

    /** 解码十六进制原始交易并返回 JSON 字符串 */
    @JsonRpcMethod(value = "decoderawtransaction", required = true)
    String decodeRawTransactionToString(String txHex);

    /** 将十六进制签名交易广播到网络 */
    @JsonRpcMethod(value = "sendrawtransaction", required = true)
    String sendRawTransaction(String hex);
}
