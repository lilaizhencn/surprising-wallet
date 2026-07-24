package com.surprising.wallet.chain.solana;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.programs.AssociatedTokenProgram;
import org.p2p.solanaj.programs.TokenProgram;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Solana 链地址管理服务，负责创建和管理链上地址（SOL 原生地址和 SPL Token ATA 地址）。
 *
 * <p>原生地址通过 {@link SolanaKeyService} 派生 Ed25519 密钥后，取公钥的 Base58 编码。
 * SPL Token 地址使用 Associated Token Account（ATA）机制，
 * 通过 {@link #associatedTokenAddress} 计算确定性派生地址（PDA）。</p>
 *
 * <p>地址创建遵循幂等原则：先查数据库，已存在则直接返回，不存在则新建并持久化。</p>
 *
 * @see SolanaKeyService
 */
@Service
@RequiredArgsConstructor
public
class SolanaAddressService {

    /** 链标识常量 */
    private static final String CHAIN = "SOLANA";

    /** Solana 密钥服务 */
    private final SolanaKeyService keyService;

    /** 数据库仓库 */
    private final ChainJdbcRepository repository;

    /**
     * 创建原生 SOL 地址并持久化。
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
        HotWalletRules.requireAllowedReservedAddress(CHAIN, "SOL", "SOL", userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, "SOL", userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);
                    String address = new PublicKey(key.publicKey()).toBase58();
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .tenantId(tenantId)
                            .chain(CHAIN)
                            .assetSymbol("SOL")
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
                    return repository.findChainAddress(CHAIN, "SOL", userId, biz, derivationIndex, walletRole)
                            .orElseThrow();
                });
    }

    /**
     * 创建 SPL Token 的关联代币地址（ATA）并持久化。
     *
     * <p>先确保所有者（owner）的原生 SOL 地址已创建，
     * 然后通过 {@link #associatedTokenAddress} 计算 ATA 并持久化。</p>
     *
     * @param tenantId       租户 ID
     * @param symbol         代币符号
     * @param mintAddress    SPL Mint 地址
     * @param userId         用户 ID
     * @param biz            业务标识
     * @param derivationIndex 派生索引
     * @param walletRole     钱包角色
     * @return 创建或已存在的代币地址记录
     */
    public ChainAddressRecord createTokenAddress(UUID tenantId, String symbol, String mintAddress,
                                                  long userId, int biz,
                                                  long derivationIndex, String walletRole) {
        HotWalletRules.requireAllowedReservedAddress(CHAIN, symbol, "SOL", userId, biz, derivationIndex, walletRole);
        ChainAddressRecord owner = createNativeAddress(tenantId, userId, biz, derivationIndex, walletRole);
        return repository.findChainAddress(CHAIN, symbol, userId, biz, derivationIndex, walletRole)
                .orElseGet(() -> {
                    String ata = associatedTokenAddress(owner.getAddress(), mintAddress);
                    ChainAddressRecord record = ChainAddressRecord.builder()
                            .tenantId(tenantId)
                            .chain(CHAIN)
                            .assetSymbol(symbol)
                            .accountId(owner.getAddress())
                            .userId(userId)
                            .biz(biz)
                            .addressIndex(derivationIndex)
                            .address(ata)
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
     * 计算 SPL Token 的关联代币账户地址（ATA）。
     *
     * <p>ATA 是根据所有者地址、Token Program ID、Mint 地址
     * 通过 {@code findProgramAddress} 计算出的确定性 PDA。</p>
     *
     * @param ownerAddress 所有者地址（Base58）
     * @param mintAddress  SPL Mint 地址（Base58）
     * @return ATA 地址（Base58）
     */
    public String associatedTokenAddress(String ownerAddress, String mintAddress) {
        PublicKey owner = new PublicKey(ownerAddress);
        PublicKey mint = new PublicKey(mintAddress);
        return PublicKey.findProgramAddress(
                        List.of(owner.toByteArray(), TokenProgram.PROGRAM_ID.toByteArray(), mint.toByteArray()),
                        AssociatedTokenProgram.PROGRAM_ID)
                .getAddress()
                .toBase58();
    }
}
