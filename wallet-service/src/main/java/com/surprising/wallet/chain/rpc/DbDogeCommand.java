package com.surprising.wallet.chain.rpc;

import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import org.springframework.stereotype.Component;

/**
 * DOGE JSON-RPC 客户端，连接到 Dogecoin 节点（bitcoind 兼容）。
 *
 * <p>继承 {@link DbBtcLikeJsonRpcCommand} 的通用 JSON-RPC 逻辑，实现 {@link DogeCommand} 接口。
 */
@Component
public
class DbDogeCommand extends DbBtcLikeJsonRpcCommand implements DogeCommand {
    public DbDogeCommand(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {        super("DOGE", repository, rpcNodeService);
    }
}
