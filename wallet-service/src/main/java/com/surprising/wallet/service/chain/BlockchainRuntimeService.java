package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Adapter-first runtime entry point.
 *
 * <p>Business code should resolve chain behavior through this service and
 * {@link BlockchainAdapter}. Address generation is handled by the DB-backed
 * chain address runtime; chain actions such as scanning and broadcasting must
 * be implemented by the adapter for that chain.</p>
 */
@Service
@RequiredArgsConstructor
public class BlockchainRuntimeService {
    private static final String BITCOIN_LIKE_FAMILY = "bitcoin-like";

    private final BlockchainAdapterRegistry adapterRegistry;
    private final ChainJdbcRepository repository;
    private final ChainAddressRuntime addressRuntime;

    public RuntimeChain requireRuntime(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(normalizeChain(chain))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for chain " + chain));
        ChainType chainType = requireChainType(profile.getChain());
        BlockchainAdapter adapter = adapterRegistry.require(chainType);
        return new RuntimeChain(
                chainType,
                profile.getChain(),
                profile.getNetwork(),
                profile.getFamily(),
                profile.getNativeSymbol(),
                profile.getRuntimeCurrencyId(),
                adapter.family(),
                adapter.describe(),
                adapter.capabilities()
        );
    }

    public BlockchainAdapter requireAdapter(String chain) {
        return adapterRegistry.require(requireRuntime(chain).chainType());
    }

    public Address generateDepositAddress(String chain, long userId, int biz) {
        RuntimeChain runtime = requireRuntime(chain);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.ADDRESS_GENERATION)) {
            return adapter.generateDepositAddress(runtime.chainType(), userId, biz);
        }
        return addressRuntime.generateDepositAddress(runtime.chainType(), userId, biz);
    }

    public Address generateDepositAddressAtIndex(
            String chain, long userId, int biz, long childIndex) {
        RuntimeChain runtime = requireRuntime(chain);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.ADDRESS_GENERATION)) {
            return adapter.generateDepositAddressAtIndex(
                    runtime.chainType(), userId, biz, childIndex);
        }
        return addressRuntime.generateDepositAddressAtIndex(
                runtime.chainType(), userId, biz, childIndex);
    }

    public boolean checkAddress(String chain, String address) {
        RuntimeChain runtime = requireRuntime(chain);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.ADDRESS_VALIDATION)) {
            return adapter.checkAddress(runtime.chainType(), address);
        }
        return addressRuntime.checkAddress(runtime.chainType(), address);
    }

    public long depositConfirmationThreshold(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.CONFIRMATION_POLICY)) {
            return adapter.depositConfirmationThreshold(runtime.chainType());
        }
        return profile(asset).getDepositConfirmations();
    }

    public long dustThresholdAtomic(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.DUST_POLICY)) {
            return adapter.dustThresholdAtomic(runtime.chainType());
        }
        return 0L;
    }

    public long bestHeight(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        return adapter.bestHeight(runtime.chainType());
    }

    public List<TransactionDTO> findRelatedTransactions(AssetRuntimeMetadata asset, long height) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        return adapter.findRelatedTransactions(runtime.chainType(), height);
    }

    public void updateTransactionConfirmations(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.CONFIRMATION_REFRESH)) {
            adapter.updateTransactionConfirmations(runtime.chainType());
        }
    }

    public void updateTotalBalance(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.BALANCE_REFRESH)) {
            adapter.updateTotalBalance(runtime.chainType());
        }
    }

    public String broadcastSignedTransaction(AssetRuntimeMetadata asset, WithdrawTransaction transaction) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        return adapter.broadcastSignedTransaction(runtime.chainType(), transaction);
    }

    public RuntimeChain requireRuntime(AssetRuntimeMetadata asset) {
        return requireRuntime(chainName(asset));
    }

    public AssetRuntimeMetadata assetMetadata(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(normalizeChain(chain))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for chain " + chain));
        return AssetRuntimeMetadata.fromProfile(profile, nativeAsset(profile));
    }

    public boolean isBitcoinLikeRuntime(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(normalizeChain(chain))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for chain " + chain));
        return BITCOIN_LIKE_FAMILY.equalsIgnoreCase(profile.getFamily());
    }

    public boolean isBitcoinLikeRuntime(AssetRuntimeMetadata asset) {
        return asset != null && repository.isRuntimeCurrencyFamily(asset.getIndex(), BITCOIN_LIKE_FAMILY);
    }

    public String chainName(AssetRuntimeMetadata asset) {
        return repository.findChainByRuntimeCurrencyId(asset.getIndex())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + asset.getIndex()));
    }

    public String scannerName(AssetRuntimeMetadata asset) {
        return chainName(asset).toLowerCase(Locale.ROOT) + "-block-scanner";
    }

    public String nativeSymbol(String chain) {
        return requireRuntime(chain).nativeSymbol();
    }

    public AssetRuntimeMetadata assetMetadata(int runtimeCurrencyId) {
        AccountChainProfile profile = repository.findProfileByRuntimeCurrencyId(runtimeCurrencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + runtimeCurrencyId));
        return AssetRuntimeMetadata.fromProfile(profile, nativeAsset(profile));
    }

    private ChainAsset nativeAsset(AccountChainProfile profile) {
        return repository.findAsset(profile.getChain(), profile.getNativeSymbol()).orElse(null);
    }

    private ChainType requireChainType(String chain) {
        try {
            return ChainType.valueOf(normalizeChain(chain));
        } catch (RuntimeException e) {
            throw new IllegalStateException("unsupported chain type " + chain, e);
        }
    }

    private String normalizeChain(String chain) {
        if (chain == null || chain.isBlank()) {
            throw new IllegalArgumentException("chain is required");
        }
        return chain.trim().toUpperCase(Locale.ROOT);
    }

    private AccountChainProfile profile(AssetRuntimeMetadata asset) {
        return repository.findProfileByChain(chainName(asset))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + asset.chain()));
    }

    public record RuntimeChain(
            ChainType chainType,
            String chain,
            String network,
            String family,
            String nativeSymbol,
            Integer runtimeCurrencyId,
            String adapterFamily,
            String adapterDescription,
            java.util.Set<BlockchainAdapter.Capability> capabilities
    ) {
    }
}
