package com.surprising.wallet.chain.monero;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Monero 链地址管理服务，通过 monero-wallet-rpc 创建和管理子地址。
 *
 * <p>Monero 使用子地址（subaddress）机制，每个账户可以有多个子地址用于收款。
 * 主地址对应 subaddress_index=0，新地址通过 create_address 创建。</p>
 *
 * @see MoneroWalletRpcClient
 */
@Service
@RequiredArgsConstructor
public
class MoneroAddressService {

    /** 链标识 */
    private static final String CHAIN = MoneroWalletRpcClient.CHAIN;

    /** 原生币符号 */
    private static final String SYMBOL = MoneroWalletRpcClient.SYMBOL;

    /** Monero 钱包 RPC 客户端 */
    private final MoneroWalletRpcClient walletRpcClient;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;
    public ChainAddressRecord createNativeAddress(long userId, int biz, long preferredIndex, String walletRole) {
        MoneroWalletRpcClient.Subaddress subaddress = userId == 0 && biz == 0 && preferredIndex == 0
                ? walletRpcClient.primaryAddress()
                : walletRpcClient.createAddress(label(userId, biz, preferredIndex));
        ChainAddressRecord record = ChainAddressRecord.builder()
                .chain(CHAIN)
                .assetSymbol(SYMBOL)
                .accountId(subaddress.address())
                .userId(userId)
                .biz(biz)
                .addressIndex((long) subaddress.addressIndex())
                .address(subaddress.address())
                .ownerAddress(subaddress.address())
                .derivationPath("monero-wallet-rpc:m/0/" + subaddress.addressIndex())
                .walletRole(walletRole)
                .enabled(true)
                .build();
        repository.upsertChainAddress(record);
        return repository.findChainAddress(CHAIN, SYMBOL, userId, biz, subaddress.addressIndex(), walletRole)
                .orElseThrow();
    }
    private static String label(long userId, int biz, long preferredIndex) {
        return "surprising-wallet user=" + userId + " biz=" + biz + " requested-index=" + preferredIndex;
    }
}
