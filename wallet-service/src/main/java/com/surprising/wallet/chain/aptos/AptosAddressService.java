package com.surprising.wallet.chain.aptos;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Aptos 链地址管理服务，负责创建和管理链上地址（原生 APT 地址和 FA 代币地址）。
 *
 * <p>地址通过 {@link AptosKeyService} 派生 Ed25519 密钥后计算得出。
 * 代币地址与原生地址共用同一个链上账户，仅资产符号不同。</p>
 *
 * <p>所有地址创建前会经过 {@link HotWalletRules} 的合法性校验。</p>
 */
@Service
@RequiredArgsConstructor
public class AptosAddressService {

    /** 链标识常量 */
    private static final String CHAIN = "APTOS";

    /** 密钥服务 */
    private final AptosKeyService keyService;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /**
     * 创建原生 APT 充值/收款地址。
     *
     * <p>如果地址已存在则直接返回，否则派生密钥、生成地址并持久化。</p>
     *
     * @param userId          用户 ID
     * @param biz             业务 ID
     * @param derivationIndex 派生索引
     * @param walletRole      钱包角色（如 "DEPOSIT"）
     * @return 地址记录
     */
    public ChainAddressRecord createNativeAddress(long userId, int biz, long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(CHAIN, "APT", "APT", userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, "APT", userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);
                    String address = AptosKeyService.address(key.publicKey());
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .chain(CHAIN)
                            .assetSymbol("APT")
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
                    return repository.findChainAddress(CHAIN, "APT", userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }

    /**
     * 创建 FA 代币的充值/收款地址。
     *
     * <p>代币地址复用原生 APT 地址（Aptos 上同一账户可持有多种资产），
     * 但数据库中以不同资产符号存储独立记录。</p>
     *
     * @param symbol          代币符号
     * @param userId          用户 ID
     * @param biz             业务 ID
     * @param derivationIndex 派生索引
     * @param walletRole      钱包角色
     * @return 地址记录
     */
    public ChainAddressRecord createTokenAddress(String symbol, long userId, int biz,
                                                 long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(CHAIN, symbol, "APT", userId, biz, derivationIndex, walletRole);
        ChainAddressRecord owner = createNativeAddress(userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .chain(CHAIN)
                            .assetSymbol(symbol)
                            .accountId(owner.getAccountId())
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(owner.getAddress())
                            .ownerAddress(owner.getAddress())
                            .derivationPath(owner.getDerivationPath())
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }
}
