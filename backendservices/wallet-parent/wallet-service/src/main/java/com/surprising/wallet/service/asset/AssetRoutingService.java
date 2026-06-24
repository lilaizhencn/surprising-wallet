package com.surprising.wallet.service.asset;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * DB Asset Model routing facade.
 *
 * <p>Runtime code should resolve chain and asset metadata from chain_profile,
 * chain_asset and token_config. RuntimeAsset conversion is kept here only for
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

    public boolean isBitcoinLikeRuntimeCurrency(RuntimeAsset currency) {
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

    public RuntimeAsset runtimeAsset(int runtimeCurrencyId) {
        AccountChainProfile profile = findRuntimeProfile(runtimeCurrencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + runtimeCurrencyId));
        ChainAsset asset = chainRepository.findAsset(profile.getChain(), profile.getNativeSymbol()).orElse(null);
        return RuntimeAsset.fromProfile(profile, asset);
    }

    public RuntimeAsset runtimeAssetByChain(String chain) {
        AccountChainProfile profile = chainRepository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for chain " + chain));
        ChainAsset asset = chainRepository.findAsset(profile.getChain(), profile.getNativeSymbol()).orElse(null);
        return RuntimeAsset.fromProfile(profile, asset);
    }

    public RuntimeAsset runtimeTokenAsset(String chain, String symbol) {
        AccountChainProfile profile = chainRepository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for chain " + chain));
        TokenDefinition token = chainRepository.findToken(profile.getChain(), symbol)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled token_config/token_registry for "
                                + profile.getChain() + "/" + symbol));
        return RuntimeAsset.fromToken(profile, token);
    }

    public List<RuntimeAsset> runtimeTokenAssets(String chain) {
        AccountChainProfile profile = chainRepository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for chain " + chain));
        return chainRepository.listTokens(profile.getChain()).stream()
                .map(token -> RuntimeAsset.fromToken(profile, token))
                .toList();
    }

    public RuntimeAsset legacyMainCurrency(RuntimeAsset currency) {
        return RuntimeAsset.toMainCurrency(currency);
    }
}
