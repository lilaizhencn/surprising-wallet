package com.surprising.wallet.chain.evm;

import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.config.AccountSecp256k1KeyService;
import com.surprising.wallet.config.ChainRpcNodeService;
import com.surprising.wallet.repository.ChainJdbcRepository;
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
    /**
     * 定义 {@code NATIVE_GAS_FLOOR} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigInteger NATIVE_GAS_FLOOR = BigInteger.valueOf(21_000L);
    /**
     * 定义 {@code TOKEN_GAS_FLOOR} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigInteger TOKEN_GAS_FLOOR = BigInteger.valueOf(65_000L);
    /**
     * 定义 {@code GAS_LIMIT_MULTIPLIER} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigDecimal GAS_LIMIT_MULTIPLIER = new BigDecimal("1.20");
    /**
     * 定义 {@code BLOCK_GAS_RATIO} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigDecimal BLOCK_GAS_RATIO = new BigDecimal("0.90");
    /**
     * 定义 {@code COLLECTION_FEE_SAFETY_MULTIPLIER} 常量，作为当前组件统一使用的固定协议、网络或配置值。
     */
    private static final BigInteger COLLECTION_FEE_SAFETY_MULTIPLIER = BigInteger.TWO;

    /**
     * 保存 {@code repository}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainJdbcRepository repository;
    /**
     * 保存 {@code rpcNodeService}，用于访问当前业务所依赖的仓储、客户端或服务。
     */
    private final ChainRpcNodeService rpcNodeService;
    /**
     * 保存 {@code keyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final AccountSecp256k1KeyService keyService;
    /**
     * 保存 {@code transactionBuilder}，用于标识交易、区块或业务记录。
     */
    private final EvmTransactionBuilder transactionBuilder;

    /**
     * 发送或广播 {@code sendNative} 对应的链上请求，并返回节点处理结果。
     */
    public String sendNative(
            String chain, ChainAddressRecord from, String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        int decimals = nativeDecimals(profile);
        BigInteger value = toAtomic(amount, decimals);
        return send(profile, from, toAddress, value, "0x", nativeSymbol(profile),
                null, amount, NATIVE_GAS_FLOOR, decimals);
    }

    /**
     * 发送或广播 {@code sendToken} 对应的链上请求，并返回节点处理结果。
     */
    public String sendToken(
            String chain, ChainAddressRecord from, TokenDefinition token,
            String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        String data = transactionBuilder.buildErc20TransferPayload(toAddress, amount, token);
        return send(profile, from, token.getContractAddress(), BigInteger.ZERO, data,
                token.getSymbol(), token.getContractAddress(), amount, TOKEN_GAS_FLOOR,
                nativeDecimals(profile));
    }

    /**
     * 发送或广播 {@code send} 对应的链上请求，并返回节点处理结果。
     */
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

    /**
     * 执行 {@code reservedFee} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
    public boolean confirmWithdrawal(
            String chain, String orderNo, String symbol,
            String accountId, BigDecimal debitAmount) {
        return confirmWithdrawal(repository.requireWithdrawalTenant(chain, orderNo),
                chain, orderNo, symbol, accountId, debitAmount);
    }

    /**
     * 处理 {@code confirmWithdrawal} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
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

    /**
     * 处理 {@code confirmCollection} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
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

    /**
     * 处理 {@code confirmedReceipt} 对应的链上或钱包业务流程，并维护状态、幂等和错误边界。
     */
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

    /**
     * 写入或更新 {@code markConfirmed} 对应的业务状态，并保持关联字段与审计状态一致。
     */
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

    /**
     * 计算或估算 {@code estimateGas} 对应的金额、费用或资源消耗。
     */
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

    /**
     * 执行 {@code bufferedGasLimit} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 获取或查询 {@code rawTransaction} 对应的数据，并向调用方返回当前业务状态。
     */
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

    /**
     * 为 {@code sign} 对应的交易或消息生成签名，并保持原始数据不被改变。
     */
    private String sign(
            AccountChainProfile profile, RawTransaction transaction, Credentials credentials) {
        byte[] signed = EvmFeeSupport.gasPolicy(profile).isEip1559()
                ? TransactionEncoder.signMessage(transaction, credentials)
                : TransactionEncoder.signMessage(transaction, profile.getChainId(), credentials);
        return Numeric.toHexString(signed);
    }

    /**
     * 发送或广播 {@code broadcast} 对应的链上请求，并返回节点处理结果。
     */
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

    /**
     * 执行 {@code credentials} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private Credentials credentials(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = keyService.key(profile, from);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }

    /**
     * 执行 {@code pendingNonce} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private BigInteger pendingNonce(Web3j web3j, String address) throws Exception {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();
    }

    /**
     * 记录或保存 {@code record} 对应的数据，并遵守幂等和事务约束。
     */
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

    /**
     * 执行 {@code withWeb3} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 获取或查询 {@code profile} 对应的数据，并向调用方返回当前业务状态。
     */
    private AccountChainProfile profile(String chain) {
        return repository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for " + chain));
    }

    /**
     * 执行 {@code nativeDecimals} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
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

    /**
     * 编码 {@code toAtomic} 对应的数据，生成链上或接口所需的表示。
     */
    private BigInteger toAtomic(BigDecimal amount, int decimals) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("EVM transfer amount must be positive");
        }
        return amount.movePointRight(decimals).toBigIntegerExact();
    }

    /**
     * 执行 {@code nativeSymbol} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private String nativeSymbol(AccountChainProfile profile) {
        if (profile.getNativeSymbol() == null || profile.getNativeSymbol().isBlank()) {
            throw new IllegalStateException(
                    "EVM profile is missing native_symbol for " + profile.getChain());
        }
        return profile.getNativeSymbol();
    }

    /**
     * 执行 {@code quantity} 对应的辅助逻辑，完成数据处理并维护状态边界。
     */
    private BigInteger quantity(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " is missing");
        }
        return requireNonNegative(Numeric.decodeQuantity(value), field);
    }

    /**
     * 校验 {@code requireNonNegative} 对应的前置条件，不满足时抛出明确异常。
     */
    private BigInteger requireNonNegative(BigInteger value, String field) {
        if (value == null || value.signum() < 0) {
            throw new IllegalStateException(field + " is missing or negative");
        }
        return value;
    }

    /**
     * 转换或计算 {@code normalize} 对应的值，统一金额、格式和边界规则。
     */
    private String normalize(String address) {
        return address == null ? null : address.toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
     */
    @FunctionalInterface
    private interface Web3Request<T> {
        /**
         * 设置或更新 {@code apply} 对应的状态，并保持相关业务字段一致。
         */
        T apply(Web3j web3j, HttpService http) throws Exception;
    }

    /**
     * 封装钱包业务数据和字段约束，作为模块之间传递的明确模型。
     */
    public static class ReceiptResponse extends Response<EvmTransactionReceipt> {
    }

    /**
     * 负责 EVM 链交易、费用、扫描或 EIP-7702 相关处理。
     */
    public static class EvmTransactionReceipt extends TransactionReceipt {
        /**
         * 保存 {@code l1Fee}，用于保存金额、费用或链上执行状态。
         */
        private String l1Fee;
        /**
         * 保存 {@code gasUsedForL1}，用于保存金额、费用或链上执行状态。
         */
        private String gasUsedForL1;
        /**
         * 保存 {@code operatorFeeScalar}，用于保存金额、费用或链上执行状态。
         */
        private String operatorFeeScalar;
        /**
         * 保存 {@code operatorFeeConstant}，用于保存金额、费用或链上执行状态。
         */
        private String operatorFeeConstant;

        /**
         * 获取或查询 {@code getL1Fee} 对应的数据，供调用方读取当前状态。
         */
        public String getL1Fee() {
            return l1Fee;
        }

        /**
         * 设置或更新 {@code setL1Fee} 对应的状态，并保持相关业务字段一致。
         */
        public void setL1Fee(String l1Fee) {
            this.l1Fee = l1Fee;
        }

        /**
         * 获取或查询 {@code getGasUsedForL1} 对应的数据，供调用方读取当前状态。
         */
        public String getGasUsedForL1() {
            return gasUsedForL1;
        }

        /**
         * 设置或更新 {@code setGasUsedForL1} 对应的状态，并保持相关业务字段一致。
         */
        public void setGasUsedForL1(String gasUsedForL1) {
            this.gasUsedForL1 = gasUsedForL1;
        }

        /**
         * 获取或查询 {@code getOperatorFeeScalar} 对应的数据，供调用方读取当前状态。
         */
        public String getOperatorFeeScalar() {
            return operatorFeeScalar;
        }

        /**
         * 设置或更新 {@code setOperatorFeeScalar} 对应的状态，并保持相关业务字段一致。
         */
        public void setOperatorFeeScalar(String operatorFeeScalar) {
            this.operatorFeeScalar = operatorFeeScalar;
        }

        /**
         * 获取或查询 {@code getOperatorFeeConstant} 对应的数据，供调用方读取当前状态。
         */
        public String getOperatorFeeConstant() {
            return operatorFeeConstant;
        }

        /**
         * 设置或更新 {@code setOperatorFeeConstant} 对应的状态，并保持相关业务字段一致。
         */
        public void setOperatorFeeConstant(String operatorFeeConstant) {
            this.operatorFeeConstant = operatorFeeConstant;
        }
    }
}
