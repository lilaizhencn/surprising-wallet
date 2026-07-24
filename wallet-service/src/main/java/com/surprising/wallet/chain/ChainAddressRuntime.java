package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.chain.cardano.CardanoKeyService;
import com.surprising.wallet.chain.monero.MoneroAddressValidator;
import com.surprising.wallet.chain.near.NearKeyService;
import com.surprising.wallet.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.chain.tron.TronAddressCodec;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import com.surprising.wallet.wallet.service.HotWalletAddressService;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.base.Base58;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

/**
 * 链地址运行时服务，统一管理跨链充值地址的生成和校验。
 *
 * <p>地址生成使用 BIP44/Ed25519 派生路径，分配唯一索引后写入 chain_address 表。
 * 地址校验根据链类型使用不同的格式规则（EVM regex、TRON Base58、Solana Base58 等）。
 * userId=0, biz=0 被保留为默认热钱包地址，不可用于充值地址生成。
 */
@Service
@RequiredArgsConstructor
public
class ChainAddressRuntime {

    /** 充值地址的钱包角色 */
    private static final String WALLET_ROLE_DEPOSIT = "DEPOSIT";
    /** EVM 地址正则：0x 开头 + 40 位十六进制 */
    private static final String EVM_ADDRESS_REGEX = "^0x[a-fA-F0-9]{40}$";
    /** 32 字节十六进制地址正则（Aptos、Sui） */
    private static final String HEX_32_BYTE_ADDRESS_REGEX = "^0x[0-9a-fA-F]{64}$";

    private final ChainJdbcRepository repository;
    private final HotWalletAddressService hotWalletAddressService;

    /**
     * 为指定用户生成充值地址。
     *
     * <p>自动分配下一个可用的地址索引，通过密钥派生生成地址后写入数据库。
     * 最多重试 5 次以处理索引冲突（DuplicateKeyException）。
     *
     * @param chainType 链类型
     * @param userId    用户 ID（为 0 时抛出异常，保留给热钱包）
     * @param biz       业务类型
     * @return 生成的充值地址
     * @throws IllegalArgumentException 如果 userId=0 且 biz=0
     */
    @Transactional(rollbackFor = Throwable.class)
    public synchronized Address generateDepositAddress(ChainType chainType, long userId, int biz) {
        if (HotWalletRules.isDefaultHotUser(userId, biz)) {
            throw new IllegalArgumentException(
                    "userId=0,biz=0 is reserved for the unique default hot wallet address");
        }
        AccountChainProfile profile = profile(chainType);
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                long index = nextAddressIndex(profile, userId, biz);
                ChainAddressRecord record = hotWalletAddressService.deriveAddress(
                        profile, userId, biz, index, WALLET_ROLE_DEPOSIT);
                repository.upsertChainAddress(record);
                ChainAddressRecord saved = repository.findChainAddress(
                                profile.getChain(), profile.getNativeSymbol(), userId, biz, index, WALLET_ROLE_DEPOSIT)
                        .orElse(record);
                return toAddress(profile, saved);
            } catch (DuplicateKeyException e) {
                if (attempt == 4) {
                    throw e;
                }
            }
        }
        throw new IllegalStateException("failed to allocate a unique address index for " + chainType);
    }

    /**
     * 在指定索引位置生成充值地址。
     *
     * @param chainType  链类型
     * @param userId     用户 ID
     * @param biz        业务类型
     * @param childIndex 地址索引（0 到 Integer.MAX_VALUE）
     * @return 生成的充值地址
     */
    @Transactional(rollbackFor = Throwable.class)
    public synchronized Address generateDepositAddressAtIndex(
            ChainType chainType, long userId, int biz, long childIndex) {
        if (HotWalletRules.isDefaultHotUser(userId, biz)) {
            throw new IllegalArgumentException(
                    "userId=0,biz=0 is reserved for the unique default hot wallet address");
        }
        if (childIndex < 0 || childIndex > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("childIndex must be between 0 and 2147483647");
        }
        AccountChainProfile profile = profile(chainType);
        ChainAddressRecord record = hotWalletAddressService.deriveAddress(
                profile, userId, biz, childIndex, WALLET_ROLE_DEPOSIT);
        repository.upsertChainAddress(record);
        ChainAddressRecord saved = repository.findChainAddress(
                        profile.getChain(), profile.getNativeSymbol(), userId, biz,
                        childIndex, WALLET_ROLE_DEPOSIT)
                .orElse(record);
        return toAddress(profile, saved);
    }
    public boolean checkAddress(ChainType chainType, String address) {
        if (!StringUtils.hasText(address)) {
            return false;
        }
        String value = address.trim();
        return switch (chainType) {
            case ETH, BNB, POLYGON, ARBITRUM, OPTIMISM, BASE, AVAX_C, HYPEREVM,
                    MANTLE, LINEA, SCROLL, UNICHAIN, HYPERCORE -> value.matches(EVM_ADDRESS_REGEX);
            case TRON -> TronAddressCodec.isValidBase58(value);
            case XRP -> isValidXrpAddress(value);
            case SOLANA -> isValidSolanaAddress(value);
            case TON -> isValidTonAddress(value);
            case APTOS, SUI -> value.matches(HEX_32_BYTE_ADDRESS_REGEX);
            case ADA -> CardanoKeyService.isValidAddress(value);
            case DOT -> PolkadotKeyService.isValidSs58Address(value);
            case NEAR -> NearKeyService.isValidAccountId(value);
            case XMR -> MoneroAddressValidator.isValid(value);
            default -> throw new IllegalStateException(
                    "address validation is not implemented by generic runtime for " + chainType);
        };
    }
    private long nextAddressIndex(AccountChainProfile profile, long userId, int biz) {
        return repository.findMaxChainAddressIndex(
                        profile.getChain(), profile.getNativeSymbol(), userId, biz, WALLET_ROLE_DEPOSIT)
                .map(value -> value + 1L)
                .orElse(0L);
    }
    private AccountChainProfile profile(ChainType chainType) {
        return repository.findProfileByChain(chainType.name())
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chainType.name()));
    }
    private Address toAddress(AccountChainProfile profile, ChainAddressRecord record) {
        return Address.builder()
                .address(record.getAddress())
                .network(profile.getNetwork())
                .scriptType(profile.getFamily())
                .derivationPath(record.getDerivationPath())
                .biz(record.getBiz())
                .currency(profile.getNativeSymbol().toLowerCase(Locale.ROOT))
                .userId(record.getUserId())
                .index(Math.toIntExact(record.getAddressIndex()))
                .balance(BigDecimal.ZERO)
                .nonce(0)
                .createDate(Date.from(Instant.now()))
                .updateDate(Date.from(Instant.now()))
                .build();
    }
    private boolean isValidXrpAddress(String address) {
        try {
            org.xrpl.xrpl4j.model.transactions.Address.of(address).validateAddress();
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
    private boolean isValidSolanaAddress(String address) {
        try {
            return Base58.decode(address).length == 32;
        } catch (RuntimeException e) {
            return false;
        }
    }
    private boolean isValidTonAddress(String address) {
        try {
            org.ton.ton4j.address.Address.of(address);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
