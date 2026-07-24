package com.surprising.wallet.chain.rpc;

import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.springframework.stereotype.Component;

/**
 * BTC JSON-RPC 客户端，连接到 BTC 节点（bitcoind 兼容）。
 *
 * <p>继承 {@link DbBtcLikeJsonRpcCommand} 的通用 JSON-RPC 逻辑，实现 {@link BtcCommand} 接口。
 */
@Component
public
class DbBtcCommand extends DbBtcLikeJsonRpcCommand implements BtcCommand {
    public DbBtcCommand(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {        super("BTC", repository, rpcNodeService);
    }
}
