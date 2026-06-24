package com.surprising.wallet.service.asset;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.currency.CurrencyEnum;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

/**
 * DB Asset Model routing facade.
 *
 * <p>Runtime code should resolve chain and asset metadata from chain_profile,
 * chain_asset and token_config. CurrencyEnum conversion is kept here only for
 * legacy wallet beans, sharded tables, queues and signer adapters.</p>
 */
@Service
@RequiredArgsConstructor
public class AssetRoutingService {
    public static final String BITCOIN_LIKE_FAMILY = "bitcoin-like";

    private final ChainJdbcRepository chainRepository;

    public Optional<AccountChainProfile> findRuntimeProfile(int runtimeCurrencyId) {
        return chainRepository.findProfileByRuntimeCurrencyId(runtimeCurrencyId);
    }

    public boolean hasRuntimeProfile(int runtimeCurrencyId) {
        return findRuntimeProfile(runtimeCurrencyId).isPresent();
    }

    public boolean isBitcoinLikeRuntimeCurrency(int runtimeCurrencyId) {
        return chainRepository.isRuntimeCurrencyFamily(runtimeCurrencyId, BITCOIN_LIKE_FAMILY);
    }

    public boolean isBitcoinLikeRuntimeCurrency(CurrencyEnum currency) {
        return currency != null && isBitcoinLikeRuntimeCurrency(currency.getIndex());
    }

    public Optional<String> findChainForRuntimeCurrencyId(int runtimeCurrencyId) {
        return chainRepository.findChainByRuntimeCurrencyId(runtimeCurrencyId)
                .map(chain -> chain.toUpperCase(Locale.ROOT));
    }

    public String requireChainForRuntimeCurrencyId(int runtimeCurrencyId) {
        return findChainForRuntimeCurrencyId(runtimeCurrencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + runtimeCurrencyId));
    }

    public String requireNativeSymbolForRuntimeCurrencyId(int runtimeCurrencyId) {
        return findRuntimeProfile(runtimeCurrencyId)
                .map(AccountChainProfile::getNativeSymbol)
                .map(symbol -> symbol.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile.native_symbol for runtime_currency_id " + runtimeCurrencyId));
    }

    public String scannerName(int runtimeCurrencyId) {
        return requireChainForRuntimeCurrencyId(runtimeCurrencyId).toLowerCase(Locale.ROOT) + "-block-scanner";
    }

    public CurrencyEnum legacyCurrency(int runtimeCurrencyId) {
        return CurrencyEnum.parseValue(runtimeCurrencyId);
    }

    public CurrencyEnum legacyMainCurrency(CurrencyEnum currency) {
        return CurrencyEnum.toMainCurrency(currency);
    }
}
