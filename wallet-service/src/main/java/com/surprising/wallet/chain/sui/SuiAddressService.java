package com.surprising.wallet.chain.sui;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.UUID;

/**
 * Sui 链地址管理服务，负责创建和管理链上地址。
 *
 * <p>地址通过 {@link SuiKeyService} 派生 Ed25519 密钥后计算得出（Blake2b256）。
 * SUI 原生币和 Coin&lt;T&gt; 代币共用同一个链上地址，以不同资产符号存储独立记录。</p>
 */
@Service
@RequiredArgsConstructor
public
class SuiAddressService {

    /** 链标识常量 */
    private static final String CHAIN = "SUI";

    /** Sui 密钥服务 */
    private final SuiKeyService keyService;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    public ChainAddressRecord createNativeAddress(UUID tenantId, long userId, int biz,
                                                  long derivationIndex, String walletRole) {
        return createAddress(tenantId, "SUI", userId, biz, derivationIndex, walletRole);
    }

    public ChainAddressRecord createCoinAddress(UUID tenantId, String symbol, long userId, int biz,
                                                long derivationIndex, String walletRole) {
        return createAddress(tenantId, symbol, userId, biz, derivationIndex, walletRole);
    }

    private ChainAddressRecord createAddress(UUID tenantId, String symbol, long userId, int biz,
                                             long derivationIndex, String walletRole) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        HotWalletRules.requireAllowedReservedAddress(CHAIN, symbol, "SUI", userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);
                    String address = SuiKeyService.address(key.publicKey());
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .tenantId(tenantId)
                            .chain(CHAIN)
                            .assetSymbol(symbol)
                            .accountId(address)
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
                            .ownerAddress(address)
                            .derivationPath(key.derivationPath())
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }
}
