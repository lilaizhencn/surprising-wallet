package com.surprising.wallet.account.service;

import com.surprising.wallet.chain.xrp.XrpTransactionService;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
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
    private final ChainJdbcRepository repository;
    private final XrpTransactionService xrpTransactionService;

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

    TokenDefinition requireToken(String chain, String symbol) {
        return repository.findToken(chain, symbol)
                .orElseThrow(() -> new IllegalStateException(
                        "missing token_config for " + chain + "/" + symbol));
    }

    int assetDecimals(String chain, String symbol) {
        return repository.findAsset(chain, symbol)
                .map(ChainAsset::getDecimals)
                .orElseGet(() -> requireToken(chain, symbol).getDecimals());
    }

    BigDecimal toAtomicDecimal(BigDecimal amount, int decimals) {
        return new BigDecimal(toAtomicBigInteger(amount, decimals));
    }

    BigInteger toAtomicBigInteger(BigDecimal amount, int decimals) {
        return amount.movePointRight(decimals)
                .setScale(0, RoundingMode.UNNECESSARY)
                .toBigIntegerExact();
    }

    long toAtomicLong(BigDecimal amount, int decimals) {
        return toAtomicBigInteger(amount, decimals).longValueExact();
    }

    boolean isNative(AccountChainProfile profile, String symbol) {
        return symbol != null
                && symbol.equalsIgnoreCase(profile.getNativeSymbol());
    }

    String debitAccountId(
            WithdrawalOrderRecord order, ChainAddressRecord from) {
        String debitAccountId = order.getDebitAccountId();
        return debitAccountId == null || debitAccountId.isBlank()
                ? from.getAccountId()
                : debitAccountId;
    }

    BigDecimal withdrawalDebitAmount(WithdrawalOrderRecord order) {
        BigDecimal amount = order.getAmount() == null
                ? BigDecimal.ZERO
                : order.getAmount();
        BigDecimal fee = order.getFee() == null
                ? BigDecimal.ZERO
                : order.getFee();
        return amount.add(fee);
    }

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
