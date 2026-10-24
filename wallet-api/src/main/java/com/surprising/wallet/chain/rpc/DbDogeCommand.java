package com.surprising.wallet.chain.rpc;

import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainProfileRepository;
import org.springframework.stereotype.Component;

/**
 * DOGE JSON-RPC 客户端，连接到 Dogecoin 节点（bitcoind 兼容）。
 *
 * <p>继承 {@link DbBtcLikeJsonRpcCommand} 的通用 JSON-RPC 逻辑，实现 {@link DogeCommand} 接口。
 */
@Component
public class DbDogeCommand extends DbBtcLikeJsonRpcCommand implements DogeCommand {
    public DbDogeCommand(ChainProfileRepository profiles, ChainRpcNodeService rpcNodeService) {
        super("DOGE", profiles, rpcNodeService);
    }
}
