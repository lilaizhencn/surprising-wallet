package com.surprising.wallet.service.chain.rpc;

import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.surprising.wallet.common.pojo.rpc.BtcLikeBlock;
import com.surprising.wallet.common.pojo.rpc.BtcLikeRawTransaction;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;
abstract class DbBtcLikeJsonRpcCommand implements BtcLikeCommand {
    private static final int TIMEOUT_MS = 120_000;    private final String chain;    private final ChainJdbcRepository repository;    private final ChainRpcNodeService rpcNodeService;

    protected DbBtcLikeJsonRpcCommand(String chain,
                                      ChainJdbcRepository repository,
                                      ChainRpcNodeService rpcNodeService) {
        this.chain = chain;
        this.repository = repository;
        this.rpcNodeService = rpcNodeService;
    }

    @Override
    public long getBlockCount() {
        return call("getblockcount", Long.class);
    }

    @Override
    public String getBlockHash(long height) {
        return call("getblockhash", String.class, height);
    }

    @Override
    public BtcLikeBlock getBlock(String hash) {
        return call("getblock", BtcLikeBlock.class, hash);
    }

    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid, boolean verbose) {
        return getRawTransaction(txid, verbose ? 1 : 0);
    }

    @Override
    public BtcLikeRawTransaction getRawTransaction(String txid, int verbose) {
        return call("getrawtransaction", BtcLikeRawTransaction.class, txid, verbose);
    }

    @Override
    public String getRawTransactionStr(String txid) {
        return call("getrawtransaction", String.class, txid);
    }

    @Override
    public BtcLikeRawTransaction decodeRawTransactionStr(String txHex) {
        return call("decoderawtransaction", BtcLikeRawTransaction.class, txHex);
    }

    @Override
    public String decodeRawTransactionToString(String txHex) {
        return call("decoderawtransaction", String.class, txHex);
    }

    @Override
    public String sendRawTransaction(String hex) {
        return call("sendrawtransaction", String.class, hex);
    }
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
