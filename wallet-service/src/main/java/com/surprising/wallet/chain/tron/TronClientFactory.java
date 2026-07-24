package com.surprising.wallet.chain.tron;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * TRON 客户端工厂，负责创建 {@link TronTridentClient} 实例。
 *
 * <p>根据链配置自动选择 full node 和 solidity node：
 * <ul>
 *   <li>full node：用于交易广播和实时数据查询</li>
 *   <li>solidity node：用于已确认的历史数据查询（可选，如果未配置则使用 full node）</li>
 * </ul></p>
 */
@Service
@RequiredArgsConstructor
public class TronClientFactory {

    /** 链标识常量 */
    private static final String CHAIN = "TRON";

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /** RPC 节点故障转移服务 */
    private final ChainRpcNodeService rpcNodeService;
    public TronTridentClient create() {
        AccountChainProfile profile = repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for TRON"));
        ChainRpcNode fullNode = first(rpcNodeService.enabledNodes(CHAIN, profile.getNetwork(), "rpc"),
                "missing enabled TRON full node");
        ChainRpcNode solidityNode = firstOrSame(
                rpcNodeService.enabledNodes(CHAIN, profile.getNetwork(), "solidity"), fullNode);
        return new TronTridentClient(fullNode, solidityNode, rpcNodeService);
    }
    private static ChainRpcNode first(List<ChainRpcNode> nodes, String message) {
        if (nodes == null || nodes.isEmpty()) {
            throw new IllegalStateException(message);
        }
        return nodes.get(0);
    }
    private static ChainRpcNode firstOrSame(List<ChainRpcNode> nodes, ChainRpcNode fallback) {
        return nodes == null || nodes.isEmpty() ? fallback : nodes.get(0);
    }

}
