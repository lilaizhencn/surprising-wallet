package com.surprising.wallet.chain.rpc;

import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.BtcLikeBlock;
import com.surprising.wallet.sdk.bitcoinj.rpc.model.BtcLikeRawTransaction;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
/**
 * BTC-like 链 JSON-RPC 客户端抽象基类。
 *
 * <p>实现 {@link BtcLikeCommand} 接口，通过 JSON-RPC over HTTP 调用 bitcoind 兼容节点的 RPC 方法。
 * RPC URL 从数据库动态获取（通过 {@link ChainRpcNodeService}），支持故障转移。
 * 超时时间固定为 120 秒。
 *
 * <p>子类：{@link DbBtcCommand}、{@link DbBchCommand}、{@link DbDogeCommand}。
 */
abstract class DbBtcLikeJsonRpcCommand implements BtcLikeCommand {

    /** JSON-RPC HTTP 超时时间：120 秒 */
    private static final int TIMEOUT_MS = 120_000;
    /** 链名称，用于 RPC 节点路由 */
    private final String chain;
    /** 链数据仓储 */
    private final ChainJdbcRepository repository;
    /** RPC 节点管理服务 */
    private final ChainRpcNodeService rpcNodeService;

    /**
     * @param chain          链名称（如 BTC、BCH、DOGE）
     * @param repository     链数据仓储
     * @param rpcNodeService RPC 节点服务
     */
    protected DbBtcLikeJsonRpcCommand(String chain,
                                      ChainJdbcRepository repository,
                                      ChainRpcNodeService rpcNodeService) {
        this.chain = chain;
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
    }

    /**
     * 获取或查询 {@code getBlockCount} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public long getBlockCount() {
        return call("getblockcount", Long.class);
    }

    /**
     * 获取或查询 {@code getBlockHash} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public String getBlockHash(long height) {
        return call("getblockhash", String.class, height);
    }

    /**
     * 获取或查询 {@code getBlock} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public BtcLikeBlock getBlock(String hash) {
        return call("getblock", BtcLikeBlock.class, hash);
    }

    /**
     * 获取或查询 {@code getRawTransaction} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid, boolean verbose) {
        return getRawTransaction(txid, verbose ? 1 : 0);
    }

    /**
     * 获取或查询 {@code getRawTransaction} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid, int verbose) {
        return call("getrawtransaction", BtcLikeRawTransaction.class, txid, verbose);
    }

    /**
     * 获取或查询 {@code getRawTransactionStr} 对应的数据，供调用方读取当前状态。
     */
    @Override
    public String getRawTransactionStr(String txid) {
        return call("getrawtransaction", String.class, txid);
    }

    /**
     * 解析或转换 {@code decodeRawTransactionStr} 对应的数据，并校验其格式和边界。
     */
    @Override
    public BtcLikeRawTransaction decodeRawTransactionStr(String txHex) {
        return call("decoderawtransaction", BtcLikeRawTransaction.class, txHex);
    }

    /**
     * 解析或转换 {@code decodeRawTransactionToString} 对应的数据，并校验其格式和边界。
     */
    @Override
    public String decodeRawTransactionToString(String txHex) {
        return call("decoderawtransaction", String.class, txHex);
    }

    /**
     * 发送或广播 {@code sendRawTransaction} 对应的链上请求，并返回节点处理结果。
     */
    @Override
    public String sendRawTransaction(String hex) {
        return call("sendrawtransaction", String.class, hex);
    }
    /**
     * 执行 {@code call} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private <T> T call(String method, Class<T> responseType, Object... params) {
        String network = repository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chain))
                .getNetwork();
        return rpcNodeService.withFailover(chain, network, node -> {
            try {
                Map<String, String> headers = new HashMap<>();
                headers.put("Content-type", "application/json");
                headers.putAll(rpcNodeService.authHeaders(node));
                JsonRpcHttpClient client = new JsonRpcHttpClient(new URL(node.getRpcUrl()), headers);
                client.setConnectionTimeoutMillis(TIMEOUT_MS);
                client.setReadTimeoutMillis(TIMEOUT_MS);
                T result = client.invoke(method, params, responseType);
                if (result == null) {
                    throw new IllegalStateException("RPC returned empty result: " + chain + " " + method);
                }
                return result;
            } catch (Throwable e) {
                if (e instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new IllegalStateException("RPC call failed: " + chain + " " + method, e);
            }
        });
    }
}
