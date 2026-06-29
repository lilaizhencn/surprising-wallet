package com.surprising.wallet.service.chain;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAsset;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.RuntimeAsset;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import com.surprising.wallet.service.wallet.IWallet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Adapter-first runtime entry point.
 *
 * <p>Business code should resolve chain behavior through this service and
 * {@link BlockchainAdapter}. Legacy {@link IWallet}/{@link RuntimeAsset}
 * access stays here as a temporary bridge until signing, UTXO scanning, and
 * collection are fully migrated.</p>
 */
@Service
@RequiredArgsConstructor
public class BlockchainRuntimeService {
    private static final String BITCOIN_LIKE_FAMILY = "bitcoin-like";

    private final BlockchainAdapterRegistry adapterRegistry;
    private final ChainJdbcRepository repository;
    private final List<IWallet> legacyWallets;

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
                adapter.describe()
        );
    }

    public BlockchainAdapter requireAdapter(String chain) {
        return adapterRegistry.require(requireRuntime(chain).chainType());
    }

    public Address generateDepositAddress(String chain, long userId, int biz) {
        RuntimeChain runtime = requireRuntime(chain);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            return adapter.generateDepositAddress(runtime.chainType(), userId, biz);
        } catch (UnsupportedOperationException ignored) {
            return requireLegacyWallet(runtimeAsset(runtime.chain())).genNewAddress(userId, biz);
        }
    }

    public boolean checkAddress(String chain, String address) {
        RuntimeChain runtime = requireRuntime(chain);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            return adapter.checkAddress(runtime.chainType(), address);
        } catch (UnsupportedOperationException ignored) {
            return requireLegacyWallet(runtimeAsset(runtime.chain())).checkAddress(address);
        }
    }

    public long depositConfirmationThreshold(RuntimeAsset asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            return adapter.depositConfirmationThreshold(runtime.chainType());
        } catch (UnsupportedOperationException ignored) {
            return requireLegacyWallet(asset).getDepositConfirmationThreshold();
        }
    }

    public long dustThresholdAtomic(RuntimeAsset asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            return adapter.dustThresholdAtomic(runtime.chainType());
        } catch (UnsupportedOperationException ignored) {
            return requireLegacyWallet(asset).getDustThresholdAtomic();
        }
    }

    public long bestHeight(RuntimeAsset asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            return adapter.bestHeight(runtime.chainType());
        } catch (UnsupportedOperationException ignored) {
            return requireLegacyWallet(asset).getBestHeight();
        }
    }

    public List<TransactionDTO> findRelatedTransactions(RuntimeAsset asset, long height) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            return adapter.findRelatedTransactions(runtime.chainType(), height);
        } catch (UnsupportedOperationException ignored) {
            return requireLegacyWallet(asset).findRelatedTxs(height);
        }
    }

    public void updateTransactionConfirmations(RuntimeAsset asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            adapter.updateTransactionConfirmations(runtime.chainType());
        } catch (UnsupportedOperationException ignored) {
            requireLegacyWallet(asset).updateTXConfirmation(asset);
        }
    }

    public void updateTotalBalance(RuntimeAsset asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            adapter.updateTotalBalance(runtime.chainType());
        } catch (UnsupportedOperationException ignored) {
            requireLegacyWallet(asset).updateTotalCurrencyBalance();
        }
    }

    public String broadcastSignedTransaction(RuntimeAsset asset, WithdrawTransaction transaction) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        try {
            return adapter.broadcastSignedTransaction(runtime.chainType(), transaction);
        } catch (UnsupportedOperationException ignored) {
            return requireLegacyWallet(asset).sendRawTransaction(transaction);
        }
    }

    public RuntimeChain requireRuntime(RuntimeAsset asset) {
        return requireRuntime(chainName(asset));
    }

    public RuntimeAsset runtimeAsset(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(normalizeChain(chain))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for chain " + chain));
        return RuntimeAsset.fromProfile(profile, nativeAsset(profile));
    }

    public boolean isBitcoinLikeRuntime(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(normalizeChain(chain))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for chain " + chain));
        return BITCOIN_LIKE_FAMILY.equalsIgnoreCase(profile.getFamily());
    }

    public boolean isBitcoinLikeRuntime(RuntimeAsset asset) {
        return asset != null && repository.isRuntimeCurrencyFamily(asset.getIndex(), BITCOIN_LIKE_FAMILY);
    }

    public String chainName(RuntimeAsset asset) {
        return repository.findChainByRuntimeCurrencyId(asset.getIndex())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + asset.getIndex()));
    }

    public String scannerName(RuntimeAsset asset) {
        return chainName(asset).toLowerCase(Locale.ROOT) + "-block-scanner";
    }

    public String nativeSymbol(String chain) {
        return requireRuntime(chain).nativeSymbol();
    }

    public RuntimeAsset runtimeAsset(int runtimeCurrencyId) {
        AccountChainProfile profile = repository.findProfileByRuntimeCurrencyId(runtimeCurrencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + runtimeCurrencyId));
        return RuntimeAsset.fromProfile(profile, nativeAsset(profile));
    }

    private ChainAsset nativeAsset(AccountChainProfile profile) {
        return repository.findAsset(profile.getChain(), profile.getNativeSymbol()).orElse(null);
    }

    /**
     * Temporary bridge for UTXO signing/scanning code that has not yet moved to
     * native {@link BlockchainAdapter} operations.
     */
    @Deprecated(forRemoval = false)
    public IWallet requireLegacyWallet(RuntimeAsset asset) {
        IWallet wallet = findLegacyWallet(asset);
        if (wallet == null) {
            RuntimeAsset mainAsset = RuntimeAsset.toMainCurrency(asset);
            if (mainAsset != asset) {
                wallet = findLegacyWallet(mainAsset);
            }
        }
        if (wallet == null) {
            throw new IllegalStateException("legacy wallet runtime is not available for " + asset.chain());
        }
        return wallet;
    }

    private IWallet findLegacyWallet(RuntimeAsset asset) {
        for (IWallet wallet : legacyWallets) {
            if (wallet.getCurrency().sameAsset(asset)) {
                return wallet;
            }
        }
        return null;
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

    public record RuntimeChain(
            ChainType chainType,
            String chain,
            String network,
            String family,
            String nativeSymbol,
            Integer runtimeCurrencyId,
            String adapterFamily,
            String adapterDescription
    ) {
    }
}
