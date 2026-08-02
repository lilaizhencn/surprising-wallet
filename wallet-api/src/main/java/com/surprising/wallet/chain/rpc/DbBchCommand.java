package com.surprising.wallet.chain.rpc;

import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.springframework.stereotype.Component;

/**
 * BCH JSON-RPC 客户端，连接到 Bitcoin Cash 节点（bitcoind 兼容）。
 *
 * <p>继承 {@link DbBtcLikeJsonRpcCommand} 的通用 JSON-RPC 逻辑，实现 {@link BchCommand} 接口。
 */
@Component
public
class DbBchCommand extends DbBtcLikeJsonRpcCommand implements BchCommand {
    /**
     * 构造 {@code DbBchCommand}，初始化该组件运行所需的状态和依赖。
     */
    public DbBchCommand(ChainJdbcRepository repository, ChainRpcNodeService rpcNodeService) {        super("BCH", repository, rpcNodeService);
    }
}
