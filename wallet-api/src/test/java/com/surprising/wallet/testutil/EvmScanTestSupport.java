package com.surprising.wallet.testutil;

import com.surprising.wallet.chain.evm.*;
import com.surprising.wallet.common.chain.ChainRpcNode;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import org.springframework.jdbc.core.JdbcTemplate;

/** Configures the fixture database; only the RPC discovery boundary is fixed to the local node. */
public final class EvmScanTestSupport {
    private EvmScanTestSupport() { }

    public static EvmDepositScanner scanner(JdbcTemplate jdbc, ChainType chain, String rpcUrl, int confirmations) {
        return scanner(jdbc, chain, rpcUrl, confirmations, new EvmHttpServiceFactory());
    }

    public static EvmDepositScanner scanner(JdbcTemplate jdbc, ChainType chain, String rpcUrl, int confirmations,
                                             EvmHttpServiceFactory httpServices) {
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection -> {
            String url = connection.getMetaData().getURL();
            if (!url.matches("jdbc:postgresql://(?:127\\.0\\.0\\.1|localhost):5432/surprising_wallet_test_[a-z0-9_]+(?:\\?.*)?")
                    || connection.getMetaData().getDatabaseMajorVersion() != 18) {
                throw new IllegalArgumentException("EVM fixtures require an isolated local PostgreSQL 18 test database");
            }
            return null;
        });
        jdbc.update("update chain_profile set deposit_confirmations = ? where chain = ? and enabled = true",
                confirmations, chain.name());
        var profiles = new ChainProfileRepository(jdbc);
        var rpc = new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc)) {
            @Override
            public <T> T withFailover(String requestedChain, String network,
                                      java.util.function.Function<ChainRpcNode, T> request) {
                return request.apply(ChainRpcNode.builder().chain(requestedChain).network(network).rpcUrl(rpcUrl).build());
            }
        };
        return new EvmDepositScanner(profiles, new ChainAddressRepository(jdbc), new TokenConfigRepository(jdbc),
                new DepositRecordRepository(jdbc), new EvmTransactionRepository(jdbc),
                new EvmTokenTransferRepository(jdbc), new ChainScanHeightRepository(jdbc),
                DepositTestSupport.service(jdbc), new EvmLogScanner(), rpc, httpServices,
                new WalletRuntimeConfigService(profiles, new WalletSystemConfigRepository(jdbc)));
    }
}
