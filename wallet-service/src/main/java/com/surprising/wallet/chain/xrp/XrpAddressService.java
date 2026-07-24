package com.surprising.wallet.chain.xrp;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * XRP 地址服务，负责创建和管理 XRPL 地址记录。
 *
 * <p>支持：
 * <ul>
 *   <li>原生 XRP 地址创建（通过 {@link XrpKeyService} 派生 secp256k1 地址）</li>
 *   <li>发行货币（Issued Currency）地址创建（共享同一底层 XRP 地址，按 token symbol 区分）</li>
 * </ul>
 *
 * <p>地址创建遵循 BIP44 派生路径 m/44'/coinType'/biz'/userId'/index，
 * 创建后持久化到链地址表。
 */
@Service
@RequiredArgsConstructor
public
class XrpAddressService {

    /** 链标识 */
    private static final String CHAIN = "XRP";

    /** 原生代币符号 */
    private static final String NATIVE_SYMBOL = "XRP";

    /** 密钥派生服务 */
    private final XrpKeyService keyService;

    /** 链配置数据库访问 */
    private final ChainJdbcRepository repository;

    /**
     * 创建原生 XRP 地址并持久化。
     *
     * @param userId          用户 ID
     * @param biz             业务标识
     * @param derivationIndex 派生索引
     * @param walletRole      钱包角色（DEPOSIT、HOT 等）
     * @return 创建或已存在的地址记录
     */
    public ChainAddressRecord createNativeAddress(long userId, int biz, long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(
                CHAIN, NATIVE_SYMBOL, NATIVE_SYMBOL, userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, NATIVE_SYMBOL, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    AccountChainProfile profile = profile();
                    String address = keyService.address(profile, userId, biz, derivationIndex);
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .chain(CHAIN)
                            .assetSymbol(NATIVE_SYMBOL)
                            .accountId(address)
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
                            .ownerAddress(address)
                            .derivationPath(derivationPath(profile, userId, biz, derivationIndex))
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, NATIVE_SYMBOL, userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }

    /**
     * 创建发行货币（Issued Currency）地址记录。
     *
     * <p>发行货币共享底层 XRP 地址（ownerAddress），仅以 assetSymbol 区分。
     * 会先确保底层原生地址已创建。
     *
     * @param symbol          代币符号
     * @param userId          用户 ID
     * @param biz             业务标识
     * @param derivationIndex 派生索引
     * @param walletRole      钱包角色
     * @return 创建或已存在的地址记录
     */
    public ChainAddressRecord createIssuedCurrencyAddress(String symbol, long userId, int biz,
                                                          long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(
                CHAIN, symbol, NATIVE_SYMBOL, userId, biz, derivationIndex, walletRole);
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
    private AccountChainProfile profile() {
        return repository.findProfileByChain(CHAIN)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
    }
    private String derivationPath(AccountChainProfile profile, long userId, int biz, long index) {
        return String.format("m/44/%d/%d/%d/%d",
                profile.getBip44CoinType(), biz, userId, index);
    }
}
