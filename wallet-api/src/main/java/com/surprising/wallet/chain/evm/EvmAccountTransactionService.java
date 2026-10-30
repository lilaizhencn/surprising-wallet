package com.surprising.wallet.chain.evm;

import com.surprising.wallet.chain.evm.EvmTransactionReceipt.RpcResponse;
import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.ChainAssetRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.service.EvmSubmissionService;
import com.surprising.wallet.service.EvmSubmissionService.Operation;
import com.surprising.wallet.repository.EvmTransactionRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.service.AccountSecp256k1KeyService;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.service.WithdrawalSettlementService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthEstimateGas;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

/**
 * EVM 兼容链交易服务。
 *
 * <p>Gas 币种和小数位来自链配置的原生资产；交易信封由 {@code gas_policy} 决定；
 * OP Stack、Scroll 与 Arbitrum 的附加费用由 {@code fee_model} 决定。</p>
 */
@Service
@RequiredArgsConstructor
public class EvmAccountTransactionService {
    private static final BigInteger NATIVE_GAS_FLOOR = BigInteger.valueOf(21_000L);
    private static final BigInteger TOKEN_GAS_FLOOR = BigInteger.valueOf(65_000L);
    private static final BigDecimal GAS_LIMIT_MULTIPLIER = new BigDecimal("1.20");
    private static final BigDecimal BLOCK_GAS_RATIO = new BigDecimal("0.90");
    private static final BigInteger COLLECTION_FEE_SAFETY_MULTIPLIER = BigInteger.TWO;

    private final WithdrawalOrderRepository withdrawalOrderRepository;
    private final CollectionRecordRepository collectionRecordRepository;
    private final EvmTransactionRepository evmTransactionRepository;
    private final ChainProfileRepository chainProfileRepository;
    private final ChainAssetRepository chainAssetRepository;

    private final ChainRpcNodeService rpcNodeService;
    /**
     * 保存 {@code keyService}，用于保存密钥或签名材料，必须遵守敏感数据保护要求。
     */
    private final AccountSecp256k1KeyService keyService;
    private final EvmTransactionBuilder transactionBuilder;
    private final EvmHttpServiceFactory httpServices;
    private final WithdrawalSettlementService settlements;
    private final EvmSubmissionService submissions;
    private final com.surprising.wallet.repository.CustodyAssetRecoveryRepository recoveries;

    public String sendNative(
            Operation operation, String chain, ChainAddressRecord from, String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        int decimals = nativeDecimals(profile);
        BigInteger value = toAtomic(amount, decimals);
        return send(operation, profile, from, toAddress, value, "0x", nativeSymbol(profile),
                null, amount, NATIVE_GAS_FLOOR, decimals);
    }

    public String sendToken(
            Operation operation, String chain, ChainAddressRecord from, TokenDefinition token,
            String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        String data = transactionBuilder.buildErc20TransferPayload(toAddress, amount, token);
        return send(operation, profile, from, token.getContractAddress(), BigInteger.ZERO, data,
                token.getSymbol(), token.getContractAddress(), amount, TOKEN_GAS_FLOOR,
                nativeDecimals(profile));
    }

    private String send(
            Operation operation, AccountChainProfile profile, ChainAddressRecord from, String transactionTo,
            BigInteger value, String data, String symbol, String contract,
            BigDecimal amount, BigInteger gasFloor, int nativeDecimals) {
        if (!operation.tenantId().equals(from.getTenantId())) throw new IllegalArgumentException("EVM sender tenant differs from operation");
        return withWeb3(profile, (web3j, http) -> {
            var existing = submissions.existing(operation, profile.getChain());
            if (existing != null) return broadcastPrepared(web3j, existing);
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
            var prepared = submissions.prepare(operation, profile.getChain(), normalize(from.getAddress()), chainNonce, nonce -> {
                String signedRaw = nonce.equals(chainNonce) ? provisionalSigned
                        : sign(profile, rawTransaction(profile, nonce, quote, gasLimit, transactionTo, value, data), credentials);
                BigInteger reservedFeeAtomic = nonce.equals(chainNonce) ? provisionalFee
                        : reservedFee(web3j, profile, from.getAddress(), signedRaw, gasLimit, quote);
                if (!nonce.equals(chainNonce)) EvmFeeSupport.requireBalance(web3j, from.getAddress(), value.add(reservedFeeAtomic), "EVM sender");
                return EvmTransactionRecord.builder().chain(profile.getChain()).txHash(Hash.sha3(signedRaw))
                        .fromAddress(normalize(from.getAddress())).toAddress(normalize(transactionTo)).assetSymbol(symbol)
                        .contractAddress(contract).amount(amount)
                        .fee(EvmFeeSupport.atomicToNative(reservedFeeAtomic,nativeDecimals,RoundingMode.UP))
                        .nonce(nonce.longValueExact()).confirmations(0).status("PREPARED").rawPayload(signedRaw).build();
            });
            return broadcastPrepared(web3j, prepared);
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

    public boolean confirmWithdrawal(java.util.UUID tenantId, String chain, String orderNo) {
        String txHash = withdrawalOrderRepository.findTxHash(chain, orderNo, tenantId).orElseThrow();
        EvmTransactionReceipt receipt = confirmedReceipt(chain, txHash).orElse(null);
        if (receipt == null) {
            return false;
        }
        markConfirmed(chain, txHash, receipt);
        return receipt.isStatusOK() ? settlements.confirm(tenantId, chain, orderNo, txHash)
                : settlements.fail(tenantId, chain, orderNo, txHash, "EVM receipt confirmed failure");
    }

    public boolean confirmCollection(
            java.util.UUID tenantId, String chain, String collectionNo) {
        String txHash = collectionRecordRepository.findTxHash(tenantId, chain, collectionNo).orElseThrow();
        EvmTransactionReceipt receipt = confirmedReceipt(chain, txHash).orElse(null);
        if (receipt == null) {
            return false;
        }
        markConfirmed(chain, txHash, receipt);
        if (!receipt.isStatusOK()) {
            return collectionRecordRepository.updateStatus(tenantId,chain,collectionNo,"FAILED",txHash,"EVM receipt confirmed failure",null) == 1;
        }
        return collectionRecordRepository.markConfirmed(tenantId, chain, collectionNo, txHash) == 1;
    }

    private Optional<EvmTransactionReceipt> confirmedReceipt(String chain, String txHash) {
        AccountChainProfile profile = profile(chain);
        return withWeb3(profile, (web3j, http) -> {
            RpcResponse response = new Request<>(
                    "eth_getTransactionReceipt", List.of(txHash), http, RpcResponse.class).send();
            if (response.hasError()) {
                throw new IllegalStateException("eth_getTransactionReceipt failed: "
                        + response.getError().getMessage());
            }
            EvmTransactionReceipt receipt = response.getResult();
            if (receipt == null || receipt.getBlockNumber() == null) {
                return Optional.empty();
            }
            if (receipt.getTransactionHash() == null || !txHash.equalsIgnoreCase(receipt.getTransactionHash())
                    || receipt.getBlockHash() == null) throw new IllegalStateException("EVM receipt identity is incomplete");
            var canonical = web3j.ethGetBlockByNumber(org.web3j.protocol.core.DefaultBlockParameter.valueOf(receipt.getBlockNumber()),false).send().getBlock();
            if (canonical == null || !receipt.getBlockHash().equalsIgnoreCase(canonical.getHash())) return Optional.empty();
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
            evmTransactionRepository.upsert(EvmTransactionRecord.builder()
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
                    .status(receipt.isStatusOK() ? "CONFIRMED" : "FAILED")
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

    private String broadcastPrepared(Web3j web3j, EvmTransactionRecord prepared) throws Exception {
        String hash = prepared.getTxHash();
        if (!Hash.sha3(prepared.getRawPayload()).equalsIgnoreCase(hash)) throw new IllegalStateException("prepared EVM hash mismatch");
        EthSendTransaction sent = web3j.ethSendRawTransaction(prepared.getRawPayload()).send();
        if (sent.hasError()) {
            var known = web3j.ethGetTransactionByHash(hash).send();
            if (!known.hasError() && known.getTransaction().isPresent()) return hash;
            throw new IllegalStateException("EVM broadcast outcome remains unknown");
        }
        if (sent.getTransactionHash() == null || !hash.equalsIgnoreCase(sent.getTransactionHash())) {
            throw new IllegalStateException("RPC transaction hash differs from committed signed hash");
        }
        return hash;
    }

    /** Re-broadcast only the committed bytes, with the original nonce, independently of new-work switches. */
    public boolean recover(Operation operation, String chain) {
        var existing = submissions.existing(operation,chain);
        if (existing == null) return false;
        String hash = withWeb3(profile(chain),(web3j,http) -> broadcastPrepared(web3j,existing));
        return switch (operation.kind()) {
            case WITHDRAWAL -> withdrawalOrderRepository.recoverPreparedSent(operation.tenantId(),chain,operation.reference(),hash) == 1;
            case COLLECTION -> collectionRecordRepository.recoverPreparedSent(operation.tenantId(),chain,operation.reference(),hash) == 1;
            case RECOVERY -> recoveries.recoverPreparedBroadcast(operation.tenantId(),UUID.fromString(operation.reference()),hash) == 1;
        };
    }

    private Credentials credentials(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = keyService.key(profile, from);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }

    private BigInteger pendingNonce(Web3j web3j, String address) throws Exception {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                .send().getTransactionCount();
    }

    private <T> T withWeb3(AccountChainProfile profile, Web3Request<T> request) {
        return rpcNodeService.withFailover(profile.getChain(), profile.getNetwork(), node -> {
            HttpService http = httpServices.create(node.getRpcUrl(), rpcNodeService.authHeaders(node));
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
        return chainProfileRepository.findAccountByChain(chain)
                .orElseThrow(() -> new IllegalStateException(
                        "missing enabled chain_profile for " + chain));
    }

    private int nativeDecimals(AccountChainProfile profile) {
        var asset = chainAssetRepository.findActiveDefinition(profile.getChain(), nativeSymbol(profile))
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

}
