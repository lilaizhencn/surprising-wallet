package com.surprising.wallet.service;

import com.surprising.wallet.chain.xrp.XrpTransactionService;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * 账户链资产解析与精度换算服务。
 *
 * <p>集中处理原生币判定、Token/地址解析、展示金额与最小单位换算，以及归集手续费
 * 预留，避免这些资金规则散落在工作流编排代码中。</p>
 */
@Service
@RequiredArgsConstructor
class AccountChainAssetService {
    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code xrpTransactionService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final XrpTransactionService xrpTransactionService;

    /**
     * 处理 {@code collectionAmount} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    BigDecimal collectionAmount(AccountChainProfile profile,
                                CollectionCandidateRecord candidate,
                                BigDecimal evmFeeReserve) {
        BigDecimal amount = candidate.getAmount() == null
                ? BigDecimal.ZERO
                : candidate.getAmount();
        if (!isNative(profile, candidate.getAssetSymbol())) {
            return amount;
        }
        if ("evm".equalsIgnoreCase(profile.getFamily())
                && repository.isEvm7702Managed(
                        profile.getChain(), profile.getNetwork())) {
            return amount;
        }
        if ("XRP".equals(profile.getChain())) {
            return xrpTransactionService.collectableNativeAmount(
                    candidate.getAddress(), amount);
        }
        BigDecimal reserve = nativeCollectionFeeReserve(
                profile, candidate, evmFeeReserve);
        return amount.subtract(reserve).max(BigDecimal.ZERO);
    }

    /**
     * 校验 {@code requireAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    ChainAddressRecord requireAddress(String chain, String symbol, String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("missing source address");
        }
        return repository.findChainAddressByAddress(chain, symbol, address)
                .or(() -> repository.findChainAddressByAddress(chain, address))
                .orElseThrow(() -> new IllegalStateException(
                        "missing chain_address for "
                                + chain + "/" + symbol + " " + address));
    }

    /**
     * 校验 {@code requireAddress} 对应的前置条件，不满足时抛出明确异常。
     */
    ChainAddressRecord requireAddress(
            UUID tenantId, String chain, String symbol, String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalStateException("missing source address");
        }
        return repository.findChainAddressByAddress(
                        tenantId, chain, symbol, address)
                .or(() -> repository.findChainAddressByAddress(
                        tenantId, chain, address))
                .orElseThrow(() -> new IllegalStateException(
                        "missing tenant chain_address for "
                                + chain + "/" + symbol + " " + address));
    }

    /**
     * 校验 {@code requireToken} 对应的前置条件，不满足时抛出明确异常。
     */
    TokenDefinition requireToken(String chain, String symbol) {
        return repository.findToken(chain, symbol)
                .orElseThrow(() -> new IllegalStateException(
                        "missing token_config for " + chain + "/" + symbol));
    }

    /**
     * 获取或查询 {@code assetDecimals} 对应的数据，并向调用方返回当前业务状态。
     */
    int assetDecimals(String chain, String symbol) {
        return repository.findAsset(chain, symbol)
                .map(ChainAsset::getDecimals)
                .orElseGet(() -> requireToken(chain, symbol).getDecimals());
    }

    /**
     * 编码 {@code toAtomicDecimal} 对应的数据，生成链上或接口所需的表示。
     */
    BigDecimal toAtomicDecimal(BigDecimal amount, int decimals) {
        return new BigDecimal(toAtomicBigInteger(amount, decimals));
    }

    /**
     * 编码 {@code toAtomicBigInteger} 对应的数据，生成链上或接口所需的表示。
     */
    BigInteger toAtomicBigInteger(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals)
                .setScale(0, RoundingMode.UNNECESSARY)
                .toBigIntegerExact();
    }

    /**
     * 编码 {@code toAtomicLong} 对应的数据，生成链上或接口所需的表示。
     */
    long toAtomicLong(BigDecimal amount, int decimals) {
        return toAtomicBigInteger(amount, decimals).longValueExact();
    }

    /**
     * 判断 {@code isNative} 对应的条件是否成立，并返回明确的布尔结果。
     */
    boolean isNative(AccountChainProfile profile, String symbol) {
        return symbol != null
                && symbol.equalsIgnoreCase(profile.getNativeSymbol());
    }

    /**
     * 执行 {@code debitAccountId} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    String debitAccountId(
            WithdrawalOrderRecord order, ChainAddressRecord from) {
        String debitAccountId = order.getDebitAccountId();
        return debitAccountId == null || debitAccountId.isBlank()
                ? from.getAccountId()
                : debitAccountId;
    }

    /**
     * 处理 {@code withdrawalDebitAmount} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    BigDecimal withdrawalDebitAmount(WithdrawalOrderRecord order) {
        BigDecimal amount = order.getAmount() == null
                ? BigDecimal.ZERO
                : order.getAmount();
        BigDecimal fee = order.getFee() == null
                ? BigDecimal.ZERO
                : order.getFee();
        return amount.add(fee);
    }

    /**
     * 执行 {@code nativeCollectionFeeReserve} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private BigDecimal nativeCollectionFeeReserve(
            AccountChainProfile profile,
            CollectionCandidateRecord candidate,
            BigDecimal evmFeeReserve) {
        int decimals = assetDecimals(
                candidate.getChain(), candidate.getAssetSymbol());
        BigDecimal configured = profile.getDefaultFee() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(profile.getDefaultFee())
                        .movePointLeft(decimals);
        BigDecimal feeReserve;
        if ("evm".equalsIgnoreCase(profile.getFamily())) {
            feeReserve = configured.max(evmFeeReserve)
                    .max(new BigDecimal("0.0001"));
        } else {
            feeReserve = switch (profile.getChain()) {
                case "SOLANA" -> configured.max(new BigDecimal("0.00002"));
                case "TON" -> configured.max(new BigDecimal("0.02"));
                case "XRP" -> configured.max(new BigDecimal("0.000012"));
                case "ADA" -> configured.max(new BigDecimal("0.3"));
                case "DOT" -> configured.max(new BigDecimal("0.02"));
                case "XMR" -> configured.max(new BigDecimal("0.003"));
                case "NEAR" -> configured.max(new BigDecimal("3"));
                case "HYPERCORE" -> BigDecimal.ZERO;
                case "SUI" -> configured.max(new BigDecimal("0.02"));
                case "APTOS" -> configured.max(new BigDecimal("0.05"));
                case "TRON" -> configured.max(new BigDecimal("1"));
                default -> configured;
            };
        }
        BigDecimal dustReserve = profile.getDustThreshold() == null
                ? BigDecimal.ZERO
                : BigDecimal.valueOf(profile.getDustThreshold())
                        .movePointLeft(decimals);
        return feeReserve.add(dustReserve);
    }
}
