package com.surprising.wallet.chain.rpc;

import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainProfileRepository;
import org.springframework.stereotype.Component;

/**
 * BCH JSON-RPC 客户端，连接到 Bitcoin Cash 节点（bitcoind 兼容）。
 *
 * <p>继承 {@link DbBtcLikeJsonRpcCommand} 的通用 JSON-RPC 逻辑，实现 {@link BchCommand} 接口。
 */
@Component
public class DbBchCommand extends DbBtcLikeJsonRpcCommand implements BchCommand {
    public DbBchCommand(ChainProfileRepository profiles, ChainRpcNodeService rpcNodeService) {
        super("BCH", profiles, rpcNodeService);
    }
}
