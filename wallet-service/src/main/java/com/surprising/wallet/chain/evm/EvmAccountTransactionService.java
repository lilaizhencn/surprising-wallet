package com.surprising.wallet.chain.evm;

import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.AccountSecp256k1KeyService;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.deposit.repository.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * EVM 兼容链交易服务。
 *
 * <p>Gas 币种和小数位来自链配置的原生资产；交易信封由 {@code gas_policy} 决定；
 * OP Stack、Mantle、Scroll 与 Arbitrum 的附加费用由 {@code fee_model} 决定。</p>
 */
@Service
@RequiredArgsConstructor
public class EvmAccountTransactionService {
    private static final BigInteger NATIVE_GAS_FLOOR = BigInteger.valueOf(21_000L);
    private static final BigInteger TOKEN_GAS_FLOOR = BigInteger.valueOf(65_000L);
    private static final BigDecimal GAS_LIMIT_MULTIPLIER = new BigDecimal("1.20");
    private static final BigDecimal BLOCK_GAS_RATIO = new BigDecimal("0.90");
    private static final BigInteger COLLECTION_FEE_SAFETY_MULTIPLIER = BigInteger.TWO;

    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodeService;
    private final AccountSecp256k1KeyService keyService;
    private final EvmTransactionBuilder transactionBuilder;

    public String sendNative(
            String chain, ChainAddressRecord from, String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        int decimals = nativeDecimals(profile);
        BigInteger value = toAtomic(amount, decimals);
        return send(profile, from, toAddress, value, "0x", nativeSymbol(profile),
                null, amount, NATIVE_GAS_FLOOR, decimals);
    }

    public String sendToken(
            String chain, ChainAddressRecord from, TokenDefinition token,
            String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        String data = transactionBuilder.buildErc20TransferPayload(toAddress, amount, token);
        return send(profile, from, token.getContractAddress(), BigInteger.ZERO, data,
                token.getSymbol(), token.getContractAddress(), amount, TOKEN_GAS_FLOOR,
                nativeDecimals(profile));
    }

    private String send(
            AccountChainProfile profile, ChainAddressRecord from, String transactionTo,
            BigInteger value, String data, String symbol, String contract,
            BigDecimal amount, BigInteger gasFloor, int nativeDecimals) {
        return withWeb3(profile, (web3j, http) -> {
            EvmFeeSupport.FeeQuote quote = EvmFeeSupport.quote(web3j, profile);
            BigInteger chainNonce = pendingNonce(web3j, from.getAddress());
            BigInteger estimatedGas = estimateGas(
                    web3j, profile, from.getAddress(), transactionTo, value, data, quote);
            BigInteger gasLimit = bufferedGasLimit(web3j, estimatedGas.max(gasFloor));
            Credentials credentials = credentials(profile, from);
            RawTransaction provisionalRaw = rawTransaction(
                    profile, chainNonce, quote, gasLimit, transactionTo, value, data);
            String provisionalSigned = sign(profile, provisionalRaw, credentials);
            BigInteger provisionalFee = reservedFee(
                    web3j, profile, from.getAddress(), provisionalSigned, gasLimit, quote);
            EvmFeeSupport.requireBalance(
                    web3j, from.getAddress(), value.add(provisionalFee), "EVM sender");
            BigInteger nonce = BigInteger.valueOf(repository.reserveEvmNonce(
                    profile.getChain(), normalize(from.getAddress()), chainNonce.longValueExact()));
            String signedRaw = nonce.equals(chainNonce)
                    ? provisionalSigned
                    : sign(profile, rawTransaction(
                            profile, nonce, quote, gasLimit, transactionTo, value, data), credentials);
            BigInteger reservedFeeAtomic = nonce.equals(chainNonce)
                    ? provisionalFee
                    : reservedFee(
                            web3j, profile, from.getAddress(), signedRaw, gasLimit, quote);
            if (!nonce.equals(chainNonce)) {
                EvmFeeSupport.requireBalance(
                        web3j, from.getAddress(), value.add(reservedFeeAtomic), "EVM sender");
            }
            String txHash = broadcast(web3j, signedRaw);
            String localHash = Hash.sha3(signedRaw);
            if (!localHash.equalsIgnoreCase(txHash)) {
                throw new IllegalStateException("RPC transaction hash differs from local signed hash");
            }
            record(profile.getChain(), txHash, from.getAddress(), transactionTo, symbol, contract,
                    amount, EvmFeeSupport.atomicToNative(
                            reservedFeeAtomic, nativeDecimals, RoundingMode.UP),
                    nonce.longValueExact(), "SENT", signedRaw);
            return txHash;
        });
    }

    private BigInteger reservedFee(
            Web3j web3j, AccountChainProfile profile, String from, String signedRaw,
            BigInteger gasLimit, EvmFeeSupport.FeeQuote quote) {
        return gasLimit.multiply(quote.maxFeePerGas())
                .add(EvmFeeSupport.estimateSeparateL1Fee(
                        web3j, profile, from, signedRaw))
                .add(EvmFeeSupport.estimateOperatorFee(
                        web3j, profile, from, gasLimit));
    }

    /**
     * 为普通逐笔归集预留最低 Gas。L2 的数据费取决于最终签名字节，发送时会再次精确检查。
     */
    public BigDecimal estimateCollectionFeeReserve(String chain, int enabledTokenCount) {
        AccountChainProfile profile = profile(chain);
        int tokenCount = Math.max(0, enabledTokenCount);
        int decimals = nativeDecimals(profile);
        return withWeb3(profile, (web3j, http) -> {
            EvmFeeSupport.FeeQuote quote = EvmFeeSupport.quote(web3j, profile);
            BigInteger totalGas = NATIVE_GAS_FLOOR.add(
                    TOKEN_GAS_FLOOR.multiply(BigInteger.valueOf(tokenCount)));
            BigInteger reserve = totalGas.multiply(quote.maxFeePerGas())
                    .multiply(COLLECTION_FEE_SAFETY_MULTIPLIER);
            if (EvmFeeSupport.feeModel(profile).hasSeparateL1Fee()) {
                reserve = reserve.multiply(BigInteger.TWO);
            }
            return EvmFeeSupport.atomicToNative(reserve, decimals, RoundingMode.UP);
        });
    }

    public boolean confirmWithdrawal(
            String chain, String orderNo, String symbol,
            String accountId, BigDecimal debitAmount) {
        return confirmWithdrawal(repository.requireWithdrawalTenant(chain, orderNo),
                chain, orderNo, symbol, accountId, debitAmount);
    }

    public boolean confirmWithdrawal(
            java.util.UUID tenantId, String chain, String orderNo, String symbol,
            String accountId, BigDecimal debitAmount) {
        String txHash = repository.findWithdrawalTxHash(tenantId, chain, orderNo).orElseThrow();
        EvmTransactionReceipt receipt = confirmedReceipt(chain, txHash).orElse(null);
        if (receipt == null) {
            return false;
        }
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException("EVM transaction failed: " + txHash);
        }
        markConfirmed(chain, txHash, receipt);
        return repository.confirmWithdrawalAndSettle(
                tenantId, chain, orderNo, txHash, symbol, accountId, debitAmount);
    }

    public boolean confirmCollection(
            java.util.UUID tenantId, String chain, String collectionNo) {
        String txHash = repository.findCollectionTxHash(tenantId, chain, collectionNo).orElseThrow();
        EvmTransactionReceipt receipt = confirmedReceipt(chain, txHash).orElse(null);
        if (receipt == null) {
            return false;
        }
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException("EVM collection transaction failed: " + txHash);
        }
        markConfirmed(chain, txHash, receipt);
        return repository.markCollectionConfirmed(tenantId, chain, collectionNo, txHash) == 1;
    }

    private Optional<EvmTransactionReceipt> confirmedReceipt(String chain, String txHash) {
        AccountChainProfile profile = profile(chain);
        return withWeb3(profile, (web3j, http) -> {
            ReceiptResponse response = new Request<>(
                    "eth_getTransactionReceipt", List.of(txHash), http, ReceiptResponse.class).send();
            if (response.hasError()) {
                throw new IllegalStateException("eth_getTransactionReceipt failed: "
                        + response.getError().getMessage());
            }
            EvmTransactionReceipt receipt = response.getResult();
            if (receipt == null || receipt.getBlockNumber() == null) {
                return Optional.empty();
            }
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger confirmations = latest.subtract(receipt.getBlockNumber()).add(BigInteger.ONE);
            int required = Math.max(1, profile.getWithdrawConfirmations());
            return confirmations.compareTo(BigInteger.valueOf(required)) >= 0
                    ? Optional.of(receipt) : Optional.empty();
        });
    }

    private void markConfirmed(
            String chain, String txHash, EvmTransactionReceipt receipt) {
        AccountChainProfile profile = profile(chain);
        withWeb3(profile, (web3j, http) -> {
            BigInteger gasUsed = requireNonNegative(receipt.getGasUsed(), "receipt gasUsed");
            BigInteger effectiveGasPrice = quantity(
                    receipt.getEffectiveGasPrice(), "receipt effectiveGasPrice");
            EvmFeeSupport.FeeComponents fee = EvmFeeSupport.actualFee(
                    web3j, profile, receipt.getFrom(), gasUsed, effectiveGasPrice,
                    receipt.getBlockNumber(), receipt.getL1Fee(), receipt.getGasUsedForL1(),
                    receipt.getOperatorFeeScalar(), receipt.getOperatorFeeConstant());
            repository.recordEvmTransaction(EvmTransactionRecord.builder()
                    .chain(chain)
                    .txHash(txHash)
                    .fromAddress(receipt.getFrom())
                    .toAddress(receipt.getTo())
                    .assetSymbol(nativeSymbol(profile))
                    .amount(BigDecimal.ZERO)
                    .fee(EvmFeeSupport.atomicToNative(
                            fee.total(), nativeDecimals(profile), RoundingMode.UP))
                    .blockHeight(receipt.getBlockNumber().longValueExact())
                    .confirmations(profile.getWithdrawConfirmations())
                    .status("CONFIRMED")
                    .rawPayload(null)
                    .build());
            return null;
        });
    }

    private BigInteger estimateGas(
            Web3j web3j, AccountChainProfile profile, String from, String to,
            BigInteger value, String data, EvmFeeSupport.FeeQuote quote) throws Exception {
        Transaction request = quote.eip1559()
                ? new Transaction(from, null, null, null, to, value, data,
                        profile.getChainId(), quote.maxPriorityFeePerGas(), quote.maxFeePerGas())
                : Transaction.createFunctionCallTransaction(
                        from, null, quote.legacyGasPrice(), null, to, value, data);
        EthEstimateGas response = web3j.ethEstimateGas(request).send();
        if (response.hasError()) {
            throw new IllegalStateException("eth_estimateGas failed: "
                    + response.getError().getMessage());
        }
        return requireNonNegative(response.getAmountUsed(), "eth_estimateGas");
    }

    private BigInteger bufferedGasLimit(Web3j web3j, BigInteger estimated) throws Exception {
        BigInteger buffered = new BigDecimal(estimated).multiply(GAS_LIMIT_MULTIPLIER)
                .setScale(0, RoundingMode.UP).toBigIntegerExact();
        EthBlock.Block latest = web3j.ethGetBlockByNumber(
                DefaultBlockParameterName.LATEST, false).send().getBlock();
        if (latest == null || latest.getGasLimit() == null) {
            throw new IllegalStateException("latest EVM block is missing gasLimit");
        }
        BigInteger cap = new BigDecimal(latest.getGasLimit()).multiply(BLOCK_GAS_RATIO)
                .setScale(0, RoundingMode.DOWN).toBigIntegerExact();
        if (buffered.compareTo(cap) > 0) {
            throw new IllegalStateException("estimated transaction gas exceeds block safety limit");
        }
        return buffered;
    }

    private RawTransaction rawTransaction(
            AccountChainProfile profile, BigInteger nonce, EvmFeeSupport.FeeQuote quote,
            BigInteger gasLimit, String to, BigInteger value, String data) {
        if (!quote.eip1559()) {
            return RawTransaction.createTransaction(
                    nonce, quote.legacyGasPrice(), gasLimit, to, value, data);
        }
        return RawTransaction.createTransaction(
                profile.getChainId(), nonce, gasLimit, to, value, data,
                quote.maxPriorityFeePerGas(), quote.maxFeePerGas());
    }

    private String sign(
            AccountChainProfile profile, RawTransaction transaction, Credentials credentials) {
        byte[] signed = EvmFeeSupport.gasPolicy(profile).isEip1559()
                ? TransactionEncoder.signMessage(transaction, credentials)
                : TransactionEncoder.signMessage(transaction, profile.getChainId(), credentials);
        return Numeric.toHexString(signed);
    }

    private String broadcast(Web3j web3j, String signedRaw) throws Exception {
        EthSendTransaction sent = web3j.ethSendRawTransaction(signedRaw).send();
        if (sent.hasError()) {
            throw new IllegalStateException(sent.getError().getMessage());
        }
        if (sent.getTransactionHash() == null || sent.getTransactionHash().isBlank()) {
            throw new IllegalStateException("eth_sendRawTransaction returned no transaction hash");
        }
        return sent.getTransactionHash();
    }

    private Credentials credentials(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = keyService.key(profile, from);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }

    private BigInteger pendingNonce(Web3j web3j, String address) throws Exception {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();
    }

    private void record(
            String chain, String hash, String from, String to, String symbol, String contract,
            BigDecimal amount, BigDecimal fee, long nonce, String status, String rawPayload) {
        repository.recordEvmTransaction(EvmTransactionRecord.builder()
                .chain(chain)
                .txHash(hash)
                .fromAddress(normalize(from))
                .toAddress(normalize(to))
                .assetSymbol(symbol)
                .contractAddress(contract)
                .amount(amount)
                .fee(fee)
                .nonce(nonce)
                .confirmations(0)
                .status(status)
                .rawPayload(rawPayload)
                .build());
    }

    private <T> T withWeb3(AccountChainProfile profile, Web3Request<T> request) {
        return rpcNodeService.withFailover(profile.getChain(), profile.getNetwork(), node -> {
            HttpService http = new HttpService(node.getRpcUrl());
            Web3j web3j = Web3j.build(http);
            try {
                return request.apply(web3j, http);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                web3j.shutdown();
            }
        });
    }

    private AccountChainProfile profile(String chain) {
        return repository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for " + chain));
    }

    private int nativeDecimals(AccountChainProfile profile) {
        var asset = repository.findAsset(profile.getChain(), nativeSymbol(profile))
                .orElseThrow(() -> new IllegalStateException(
                        "missing active native chain_asset for " + profile.getChain()));
        if (!Boolean.TRUE.equals(asset.getNativeAsset()) || asset.getDecimals() == null) {
            throw new IllegalStateException(
                    "invalid native chain_asset for " + profile.getChain());
        }
        return asset.getDecimals();
    }

    private BigInteger toAtomic(BigDecimal amount, int decimals) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("EVM transfer amount must be positive");
        }
        return amount.movePointRight(decimals).toBigIntegerExact();
    }

    private String nativeSymbol(AccountChainProfile profile) {
        if (profile.getNativeSymbol() == null || profile.getNativeSymbol().isBlank()) {
            throw new IllegalStateException(
                    "EVM profile is missing native_symbol for " + profile.getChain());
        }
        return profile.getNativeSymbol();
    }

    private BigInteger quantity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is missing");
        }
        return requireNonNegative(Numeric.decodeQuantity(value), field);
    }

    private BigInteger requireNonNegative(BigInteger value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalStateException(field + " is missing or negative");
        }
        return value;
    }

    private String normalize(String address) {
        return address == null ? null : address.toLowerCase(java.util.Locale.ROOT);
    }

    @FunctionalInterface
    private interface Web3Request<T> {
        T apply(Web3j web3j, HttpService http) throws Exception;
    }

    public static class ReceiptResponse extends Response<EvmTransactionReceipt> {
    }

    public static class EvmTransactionReceipt extends TransactionReceipt {
        private String l1Fee;
        private String gasUsedForL1;
        private String operatorFeeScalar;
        private String operatorFeeConstant;

        public String getL1Fee() {
            return l1Fee;
        }

        public void setL1Fee(String l1Fee) {
            this.l1Fee = l1Fee;
        }

        public String getGasUsedForL1() {
            return gasUsedForL1;
        }

        public void setGasUsedForL1(String gasUsedForL1) {
            this.gasUsedForL1 = gasUsedForL1;
        }

        public String getOperatorFeeScalar() {
            return operatorFeeScalar;
        }

        public void setOperatorFeeScalar(String operatorFeeScalar) {
            this.operatorFeeScalar = operatorFeeScalar;
        }

        public String getOperatorFeeConstant() {
            return operatorFeeConstant;
        }

        public void setOperatorFeeConstant(String operatorFeeConstant) {
            this.operatorFeeConstant = operatorFeeConstant;
        }
    }
}
