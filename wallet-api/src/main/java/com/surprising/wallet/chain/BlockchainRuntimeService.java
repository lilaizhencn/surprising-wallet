package com.surprising.wallet.chain;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.chain.utxo.BitcoinLikeChainRuntime;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.signing.UtxoSignedTransaction;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainAssetRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/** Resolves enabled database profiles and routes address and Bitcoin-like operations. */
@Service
@RequiredArgsConstructor
public class BlockchainRuntimeService {
    private final ChainProfileRepository profiles;
    private final ChainAssetRepository assets;
    private final ChainAddressRuntime addressRuntime;
    private final BitcoinLikeChainRuntime bitcoinLikeRuntime;

    public RuntimeChain requireRuntime(String chain) {
        AccountChainProfile profile = requireProfile(chain);
        return new RuntimeChain(ChainType.valueOf(profile.getChain()), profile.getChain(),
                profile.getNetwork(), profile.getFamily(), profile.getNativeSymbol(),
                profile.getRuntimeCurrencyId());
    }

    public Address generateDepositAddress(String chain, long userId, int biz) {
        ChainType type = requireRuntime(chain).chainType();
        return isBitcoinLike(type)
                ? bitcoinLikeRuntime.generateDepositAddress(type, userId, biz)
                : addressRuntime.generateDepositAddress(type, userId, biz);
    }

    public ChainAddressRecord generateDepositAddressAtIndex(AccountChainProfile profile, long userId, int biz, long childIndex) {
        if (!Boolean.TRUE.equals(profile.getEnabled())) throw new IllegalStateException("chain profile is disabled");
        ChainType type = ChainType.valueOf(profile.getChain());
        return isBitcoinLike(type)
                ? bitcoinLikeRuntime.generateDepositAddressAtIndex(profile, userId, biz, childIndex)
                : addressRuntime.generateDepositAddressAtIndex(profile, userId, biz, childIndex);
    }

    public boolean checkAddress(String chain, String address) {
        ChainType type = requireRuntime(chain).chainType();
        if (address == null || address.isBlank()) return false;
        String value = address.trim();
        return isBitcoinLike(type)
                ? bitcoinLikeRuntime.checkAddress(type, value)
                : addressRuntime.checkAddress(type, value);
    }

    public long depositConfirmationThreshold(AssetRuntimeMetadata asset) {
        return requireProfile(chainName(asset)).getDepositConfirmations();
    }

    public long dustThresholdAtomic(AssetRuntimeMetadata asset) {
        ChainType type = requireRuntime(asset).chainType();
        return isBitcoinLike(type) ? bitcoinLikeRuntime.dustThresholdAtomic(type) : 0L;
    }

    public long bestHeight(AssetRuntimeMetadata asset) {
        return bitcoinLikeRuntime.bestHeight(requireBitcoinLike(asset));
    }

    public String broadcastSignedTransaction(AssetRuntimeMetadata asset, UtxoSignedTransaction transaction) {
        return bitcoinLikeRuntime.broadcastSignedTransaction(requireBitcoinLike(asset), transaction);
    }

    public RuntimeChain requireRuntime(AssetRuntimeMetadata asset) {
        return requireRuntime(chainName(asset));
    }

    public AssetRuntimeMetadata assetMetadata(String chain) {
        return assetMetadata(requireProfile(chain));
    }

    public AssetRuntimeMetadata assetMetadata(int runtimeCurrencyId) {
        AccountChainProfile profile = profiles.findAccountByRuntimeCurrency(runtimeCurrencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + runtimeCurrencyId));
        return assetMetadata(profile);
    }

    public boolean isBitcoinLikeRuntime(String chain) {
        return isBitcoinLike(requireRuntime(chain).chainType());
    }

    public boolean isBitcoinLikeRuntime(AssetRuntimeMetadata asset) {
        return asset != null && isBitcoinLikeRuntime(chainName(asset));
    }

    public String chainName(AssetRuntimeMetadata asset) {
        return profiles.findChainByRuntimeCurrency(asset.getIndex())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + asset.getIndex()));
    }

    public String scannerName(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        return (runtime.chain() + "-" + runtime.network()).toLowerCase(Locale.ROOT) + "-block-scanner";
    }

    public String nativeSymbol(String chain) {
        return requireRuntime(chain).nativeSymbol();
    }

    public AccountChainProfile requireProfile(String chain) {
        if (chain == null || chain.isBlank()) throw new IllegalArgumentException("chain is required");
        String normalized = chain.trim().toUpperCase(Locale.ROOT);
        return profiles.findAccountByChain(normalized)
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + normalized));
    }

    private AssetRuntimeMetadata assetMetadata(AccountChainProfile profile) {
        ChainAsset asset = assets.findActiveDefinition(profile.getChain(), profile.getNativeSymbol()).orElse(null);
        return AssetRuntimeMetadata.fromProfile(profile.getRuntimeCurrencyId(), profile.getChain(),
                profile.getNativeSymbol(), profile.getDepositConfirmations(), profile.getBip44CoinType(),
                asset == null ? null : asset.getDecimals(), asset == null ? null : asset.getContractAddress());
    }

    private ChainType requireBitcoinLike(AssetRuntimeMetadata asset) {
        ChainType type = requireRuntime(asset).chainType();
        if (!isBitcoinLike(type)) throw new IllegalArgumentException("not a Bitcoin-like chain: " + type);
        return type;
    }

    // ADA also uses UTXOs, but has its own address, transaction and settlement implementation.
    private static boolean isBitcoinLike(ChainType type) {
        return switch (type) {
            case BTC, BCH, LTC, DOGE -> true;
            default -> false;
        };
    }

    public record RuntimeChain(ChainType chainType, String chain, String network, String family,
                               String nativeSymbol, Integer runtimeCurrencyId) { }
}
