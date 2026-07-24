package com.surprising.wallet.chain.ton;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.smartcontract.wallet.v4.WalletV4R2;

import java.util.Objects;
import java.util.UUID;

/**
 * TON 链地址管理服务，负责创建和管理 TON 地址（原生 TON 地址和 Jetton Wallet 地址）。
 *
 * <p>原生地址基于 WalletV4R2 智能合约，从 Ed25519 密钥对生成链上钱包地址。
 * Jetton Wallet 地址由 TON API 解析后注册，通过 {@link TonKeyService} 关联所有者。</p>
 *
 * <p>地址格式支持：
 * <ul>
 *   <li><b>Raw</b>：0:&lt;64 hex&gt;（workchain:hash）</li>
 *   <li><b>Friendly (bounceable)</b>：EQ...（base64-url）</li>
 *   <li><b>Friendly (non-bounceable)</b>：UQ...（base64-url）</li>
 * </ul>
 * 存储和比较统一使用 raw 格式。</p>
 *
 * @see TonKeyService
 */
@Service
@RequiredArgsConstructor
public
class TonAddressService {

    /** 链标识常量 */
    private static final String CHAIN = "TON";

    /** TON 密钥服务 */
    private final TonKeyService keyService;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /**
     * 创建原生 TON 地址并持久化。
     *
     * @param tenantId       租户 ID
     * @param userId         用户 ID
     * @param biz            业务标识
     * @param derivationIndex 派生索引
     * @param walletRole     钱包角色（DEPOSIT、HOT 等）
     * @return 创建或已存在的地址记录
     */
    public ChainAddressRecord createNativeAddress(UUID tenantId, long userId, int biz,
                                                  long derivationIndex, String walletRole) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        HotWalletRules.requireAllowedReservedAddress(CHAIN, "TON", "TON", userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, "TON", userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    WalletV4R2 wallet = keyService.wallet(userId, biz, derivationIndex);
                    String address = friendly(wallet.getAddress(), false);
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .tenantId(tenantId)
                            .chain(CHAIN)
                            .assetSymbol("TON")
                            .accountId(address)
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
                            .ownerAddress(address)
                            .derivationPath(keyService.derive(userId, biz, derivationIndex).derivationPath())
                            .walletRole(walletRole)
                            .enabled(true)
                            .build();
                    repository.upsertChainAddress(record);
                    return repository.findChainAddress(CHAIN, "TON", userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }

    /**
     * 注册 Jetton Wallet 地址并持久化。
     *
     * <p>Jetton Wallet 是 TEP-74 标准中管理用户 Jetton 余额的独立合约地址。
     * 先确保所有者原生地址已创建，再以 Jetton wallet 地址作为 chain_address 记录。</p>
     *
     * @param tenantId            租户 ID
     * @param symbol              代币符号
     * @param jettonWalletAddress Jetton Wallet 合约地址
     * @param userId              用户 ID
     * @param biz                 业务标识
     * @param derivationIndex     派生索引
     * @param walletRole          钱包角色
     * @return 创建或已存在的地址记录
     */
    public ChainAddressRecord registerJettonWallet(UUID tenantId, String symbol, String jettonWalletAddress,
                                                   long userId, int biz, long derivationIndex,
                                                   String walletRole) {
        Objects.requireNonNull(tenantId, "tenantId is required");
        HotWalletRules.requireAllowedReservedAddress(CHAIN, symbol, "TON", userId, biz, derivationIndex, walletRole);
        ChainAddressRecord owner = createNativeAddress(tenantId, userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    String address = friendly(Address.of(jettonWalletAddress), true);
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .tenantId(tenantId)
                            .chain(CHAIN)
                            .assetSymbol(symbol)
                            .accountId(owner.getAddress())
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(address)
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

    /**
     * 将地址转换为 raw 格式用于比较。
     *
     * @param address 任意格式的 TON 地址
     * @return raw 格式地址（0:&lt;64 hex&gt;）
     */
    public String normalizeRaw(String address) {
        return Address.of(address).toRaw();
    }

    /**
     * 根据网络类型生成 friendly 格式地址。
     *
     * @param address    TON 地址对象
     * @param bounceable 是否生成 bounceable 格式
     * @return friendly 格式地址
     */
    private String friendly(Address address, boolean bounceable) {
        boolean testnet = repository.findProfileByChain(CHAIN)
                .map(profile -> profile.getNetwork().toLowerCase(java.util.Locale.ROOT).contains("test"))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + CHAIN));
        return address.toString(true, true, bounceable, testnet);
    }
}
