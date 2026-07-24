package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.HotWalletRules;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.service.chain.cardano.CardanoKeyService;
import com.surprising.wallet.service.chain.monero.MoneroAddressValidator;
import com.surprising.wallet.service.chain.near.NearKeyService;
import com.surprising.wallet.service.chain.polkadot.PolkadotKeyService;
import com.surprising.wallet.service.chain.tron.TronAddressCodec;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.HotWalletAddressService;
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

@Service
@RequiredArgsConstructor
public
class ChainAddressRuntime {
    private static final String WALLET_ROLE_DEPOSIT = "DEPOSIT";    private static final String EVM_ADDRESS_REGEX = "^0x[a-fA-F0-9]{40}$";    private static final String HEX_32_BYTE_ADDRESS_REGEX = "^0x[0-9a-fA-F]{64}$";    private final ChainJdbcRepository repository;    private final HotWalletAddressService hotWalletAddressService;

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
