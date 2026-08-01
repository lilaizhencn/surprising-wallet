package com.surprising.wallet.chain;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.Address;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * 负责钱包业务流程编排，并集中处理状态、校验和异常边界。
 */
@Service
@RequiredArgsConstructor
public class BlockchainRuntimeService {
    /**
     * 定义 {@code BITCOIN_LIKE_FAMILY} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final String BITCOIN_LIKE_FAMILY = "bitcoin-like";
    /**
     * 保存 {@code adapterRegistry}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final BlockchainAdapterRegistry adapterRegistry;
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code addressRuntime}，表示链、网络、资产或代币配置。
     */
    private final ChainAddressRuntime addressRuntime;
    /**
     * 校验 {@code requireRuntime} 对应的前置条件，不满足时抛出明确异常。
     */
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
    /**
     * 校验 {@code requireAdapter} 对应的前置条件，不满足时抛出明确异常。
     */
    public BlockchainAdapter requireAdapter(String chain) {
        return adapterRegistry.require(requireRuntime(chain).chainType());
    }
    /**
     * 构建或生成 {@code generateDepositAddress} 对应的结果，并执行输入和状态校验。
     */
    public Address generateDepositAddress(String chain, long userId, int biz) {
        RuntimeChain runtime = requireRuntime(chain);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.ADDRESS_GENERATION)) {
            return adapter.generateDepositAddress(runtime.chainType(), userId, biz);
        }
        return addressRuntime.generateDepositAddress(runtime.chainType(), userId, biz);
    }

    /**
     * 构建或生成 {@code generateDepositAddressAtIndex} 对应的结果，并执行输入和状态校验。
     */
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
    /**
     * 校验 {@code checkAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    public boolean checkAddress(String chain, String address) {
        RuntimeChain runtime = requireRuntime(chain);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.ADDRESS_VALIDATION)) {
            return adapter.checkAddress(runtime.chainType(), address);
        }
        return addressRuntime.checkAddress(runtime.chainType(), address);
    }
    /**
     * 处理 {@code depositConfirmationThreshold} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public long depositConfirmationThreshold(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.CONFIRMATION_POLICY)) {
            return adapter.depositConfirmationThreshold(runtime.chainType());
        }
        return profile(asset).getDepositConfirmations();
    }
    /**
     * 执行 {@code dustThresholdAtomic} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public long dustThresholdAtomic(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.DUST_POLICY)) {
            return adapter.dustThresholdAtomic(runtime.chainType());
        }
        return 0L;
    }
    /**
     * 执行 {@code bestHeight} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public long bestHeight(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        return adapter.bestHeight(runtime.chainType());
    }
    /**
     * 获取或查询 {@code findRelatedTransactions} 对应的数据，供调用方读取当前状态。
     */
    public List<TransactionDTO> findRelatedTransactions(AssetRuntimeMetadata asset, long height) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        return adapter.findRelatedTransactions(runtime.chainType(), height);
    }
    /**
     * 设置或更新 {@code updateTransactionConfirmations} 对应的状态，并保持相关业务字段一致。
     */
    public void updateTransactionConfirmations(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.CONFIRMATION_REFRESH)) {
            adapter.updateTransactionConfirmations(runtime.chainType());
        }
    }
    /**
     * 设置或更新 {@code updateTotalBalance} 对应的状态，并保持相关业务字段一致。
     */
    public void updateTotalBalance(AssetRuntimeMetadata asset) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        if (adapter.capabilities().contains(BlockchainAdapter.Capability.BALANCE_REFRESH)) {
            adapter.updateTotalBalance(runtime.chainType());
        }
    }
    /**
     * 发送或广播 {@code broadcastSignedTransaction} 对应的链上请求，并返回节点处理结果。
     */
    public String broadcastSignedTransaction(AssetRuntimeMetadata asset, WithdrawTransaction transaction) {
        RuntimeChain runtime = requireRuntime(asset);
        BlockchainAdapter adapter = adapterRegistry.require(runtime.chainType());
        return adapter.broadcastSignedTransaction(runtime.chainType(), transaction);
    }
    /**
     * 校验 {@code requireRuntime} 对应的前置条件，不满足时抛出明确异常。
     */
    public RuntimeChain requireRuntime(AssetRuntimeMetadata asset) {
        return requireRuntime(chainName(asset));
    }
    /**
     * 获取或查询 {@code assetMetadata} 对应的数据，并向调用方返回当前业务状态。
     */
    public AssetRuntimeMetadata assetMetadata(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(normalizeChain(chain))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for chain " + chain));
        return assetMetadata(profile, nativeAsset(profile));
    }
    /**
     * 判断 {@code isBitcoinLikeRuntime} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isBitcoinLikeRuntime(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(normalizeChain(chain))
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for chain " + chain));
        return BITCOIN_LIKE_FAMILY.equalsIgnoreCase(profile.getFamily());
    }
    /**
     * 判断 {@code isBitcoinLikeRuntime} 对应的条件是否成立，并返回明确的布尔结果。
     */
    public boolean isBitcoinLikeRuntime(AssetRuntimeMetadata asset) {
        return asset != null && repository.isRuntimeCurrencyFamily(asset.getIndex(), BITCOIN_LIKE_FAMILY);
    }
    /**
     * 获取或查询 {@code chainName} 对应的数据，并向调用方返回当前业务状态。
     */
    public String chainName(AssetRuntimeMetadata asset) {
        return repository.findChainByRuntimeCurrencyId(asset.getIndex())
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + asset.getIndex()));
    }
    /**
     * 扫描或观察 {@code scannerName} 对应的链上状态，并转换为业务可用结果。
     */
    public String scannerName(AssetRuntimeMetadata asset) {
        String network = repository.findNetworkByRuntimeCurrencyId(asset.getIndex())
                .map(n -> "-" + n.toLowerCase(Locale.ROOT))
                .orElse("");
        return chainName(asset).toLowerCase(Locale.ROOT) + network + "-block-scanner";
    }
    /**
     * 执行 {@code nativeSymbol} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    public String nativeSymbol(String chain) {
        return requireRuntime(chain).nativeSymbol();
    }
    /**
     * 获取或查询 {@code assetMetadata} 对应的数据，并向调用方返回当前业务状态。
     */
    public AssetRuntimeMetadata assetMetadata(int runtimeCurrencyId) {
        AccountChainProfile profile = repository.findProfileByRuntimeCurrencyId(runtimeCurrencyId)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for runtime_currency_id " + runtimeCurrencyId));
        return assetMetadata(profile, nativeAsset(profile));
    }

    /**
     * 获取或查询 {@code assetMetadata} 对应的数据，并向调用方返回当前业务状态。
     */
    private AssetRuntimeMetadata assetMetadata(AccountChainProfile profile, ChainAsset asset) {
        return AssetRuntimeMetadata.fromProfile(
                profile.getRuntimeCurrencyId(),
                profile.getChain(),
                profile.getNativeSymbol(),
                profile.getDepositConfirmations(),
                profile.getBip44CoinType(),
                asset == null ? null : asset.getDecimals(),
                asset == null ? null : asset.getContractAddress());
    }

    /**
     * 执行 {@code nativeAsset} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private ChainAsset nativeAsset(AccountChainProfile profile) {
        return repository.findAsset(profile.getChain(), profile.getNativeSymbol()).orElse(null);
    }
    /**
     * 校验 {@code requireChainType} 对应的前置条件，不满足时抛出明确异常。
     */
    private ChainType requireChainType(String chain) {
        try {
            return ChainType.valueOf(normalizeChain(chain));
        } catch (RuntimeException e) {
            throw new IllegalStateException("unsupported chain type " + chain, e);
        }
    }
    /**
     * 转换或计算 {@code normalizeChain} 对应的值，统一金额、格式和边界规则。
     */
    private String normalizeChain(String chain) {
        if (chain == null || chain.isBlank()) {
            throw new IllegalArgumentException("chain is required");
        }
        return chain.trim().toUpperCase(Locale.ROOT);
    }
    /**
     * 获取或查询 {@code profile} 对应的数据，并向调用方返回当前业务状态。
     */
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
