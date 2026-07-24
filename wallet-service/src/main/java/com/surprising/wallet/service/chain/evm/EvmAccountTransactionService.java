package com.surprising.wallet.service.chain.evm;

import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.EvmTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.config.AccountSecp256k1KeyService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class EvmAccountTransactionService {
    private static final BigInteger WEI_PER_NATIVE = new BigInteger("1000000000000000000");
    private static final BigInteger NATIVE_GAS_LIMIT = BigInteger.valueOf(21_000L);
    private static final BigInteger TOKEN_GAS_LIMIT = BigInteger.valueOf(65_000L);
    private static final BigInteger COLLECTION_FEE_SAFETY_MULTIPLIER = BigInteger.TWO;
    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodeService;
    private final AccountSecp256k1KeyService keyService;
    private final EvmTransactionBuilder transactionBuilder;
    public String sendNative(String chain, ChainAddressRecord from, String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        BigInteger valueWei = amount.movePointRight(18).toBigIntegerExact();
        return withWeb3(profile, web3j -> {
            BigInteger gasPrice = gasPrice(web3j);
            BigInteger chainNonce = pendingNonce(web3j, from.getAddress());
            BigInteger nonce = BigInteger.valueOf(repository.reserveEvmNonce(
                    chain, normalize(from.getAddress()), chainNonce.longValueExact()));
            RawTransaction tx = RawTransaction.createEtherTransaction(
                    nonce, gasPrice, NATIVE_GAS_LIMIT, toAddress, valueWei);
            String txHash = broadcast(web3j, profile.getChainId(), tx, credentials(profile, from));
            record(chain, txHash, from.getAddress(), toAddress, nativeSymbol(profile), null,
                    amount, fee(gasPrice, NATIVE_GAS_LIMIT), nonce.longValue(), "SENT", null);
            return txHash;
        });
    }

    public String sendToken(String chain, ChainAddressRecord from, TokenDefinition token,
                            String toAddress, BigDecimal amount) {
        AccountChainProfile profile = profile(chain);
        return withWeb3(profile, web3j -> {
            BigInteger gasPrice = gasPrice(web3j);
            BigInteger chainNonce = pendingNonce(web3j, from.getAddress());
            BigInteger nonce = BigInteger.valueOf(repository.reserveEvmNonce(
                    chain, normalize(from.getAddress()), chainNonce.longValueExact()));
            String data = transactionBuilder.buildErc20TransferPayload(toAddress, amount, token);
            RawTransaction tx = RawTransaction.createTransaction(
                    nonce, gasPrice, TOKEN_GAS_LIMIT, token.getContractAddress(), BigInteger.ZERO, data);
            String txHash = broadcast(web3j, profile.getChainId(), tx, credentials(profile, from));
            record(chain, txHash, from.getAddress(), toAddress, token.getSymbol(), token.getContractAddress(),
                    amount, fee(gasPrice, TOKEN_GAS_LIMIT), nonce.longValue(), "SENT", data);
            return txHash;
        });
    }
    public BigDecimal estimateCollectionFeeReserve(String chain, int enabledTokenCount) {
        AccountChainProfile profile = profile(chain);
        int tokenCount = Math.max(0, enabledTokenCount);
        return withWeb3(profile, web3j -> {
            BigInteger totalGas = NATIVE_GAS_LIMIT.add(
                    TOKEN_GAS_LIMIT.multiply(BigInteger.valueOf(tokenCount)));
            return fee(gasPrice(web3j), totalGas.multiply(COLLECTION_FEE_SAFETY_MULTIPLIER));
        });
    }

    public boolean confirmWithdrawal(String chain, String orderNo, String symbol,
                                     String accountId, BigDecimal debitAmount) {
        return confirmWithdrawal(repository.requireWithdrawalTenant(chain, orderNo),
                chain, orderNo, symbol, accountId, debitAmount);
    }

    public boolean confirmWithdrawal(java.util.UUID tenantId, String chain, String orderNo, String symbol,
                                     String accountId, BigDecimal debitAmount) {
        String txHash = repository.findWithdrawalTxHash(tenantId, chain, orderNo).orElseThrow();
        TransactionReceipt receipt = confirmedReceipt(chain, txHash).orElse(null);
        if (receipt == null) {
            return false;
        }
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException("EVM transaction failed: " + txHash);
        }
        markConfirmed(chain, txHash, receipt);
        if (repository.confirmWithdrawalAndSettle(
                tenantId, chain, orderNo, txHash, symbol, accountId, debitAmount)) {
            return true;
        }
        return false;
    }
    public boolean confirmCollection(java.util.UUID tenantId, String chain, String collectionNo) {
        String txHash = repository.findCollectionTxHash(tenantId, chain, collectionNo).orElseThrow();
        TransactionReceipt receipt = confirmedReceipt(chain, txHash).orElse(null);
        if (receipt == null) {
            return false;
        }
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException("EVM collection transaction failed: " + txHash);
        }
        markConfirmed(chain, txHash, receipt);
        if (repository.markCollectionConfirmed(tenantId, chain, collectionNo, txHash) == 1) {
            return true;
        }
        return false;
    }
    private Optional<TransactionReceipt> confirmedReceipt(String chain, String txHash) {
        AccountChainProfile profile = profile(chain);
        return withWeb3(profile, web3j -> {
            Optional<TransactionReceipt> receipt = web3j.ethGetTransactionReceipt(txHash)
                    .send()
                    .getTransactionReceipt();
            if (receipt.isEmpty() || receipt.get().getBlockNumber() == null) {
                return Optional.empty();
            }
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger confirmations = latest.subtract(receipt.get().getBlockNumber()).add(BigInteger.ONE);
            int required = Math.max(1, profile.getWithdrawConfirmations());
            return confirmations.compareTo(BigInteger.valueOf(required)) >= 0 ? receipt : Optional.empty();
        });
    }
    private void markConfirmed(String chain, String txHash, TransactionReceipt receipt) {
        BigInteger gasUsed = receipt.getGasUsed() == null ? BigInteger.ZERO : receipt.getGasUsed();
        BigInteger effectiveGasPrice = receipt.getEffectiveGasPrice() == null
                || receipt.getEffectiveGasPrice().isBlank()
                ? BigInteger.ZERO
                : Numeric.decodeQuantity(receipt.getEffectiveGasPrice());
        repository.recordEvmTransaction(EvmTransactionRecord.builder()
                .chain(chain)
                .txHash(txHash)
                .fromAddress(receipt.getFrom())
                .toAddress(receipt.getTo())
                .assetSymbol(nativeSymbol(profile(chain)))
                .amount(BigDecimal.ZERO)
                .fee(fee(effectiveGasPrice, gasUsed))
                .blockHeight(receipt.getBlockNumber() == null ? null : receipt.getBlockNumber().longValue())
                .confirmations(profile(chain).getWithdrawConfirmations())
                .status("CONFIRMED")
                .rawPayload(receipt.toString())
                .build());
    }
    private String broadcast(Web3j web3j, Long chainId, RawTransaction tx, Credentials credentials) throws Exception {
        byte[] signed = TransactionEncoder.signMessage(tx, chainId, credentials);
        EthSendTransaction sent = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        if (sent.hasError()) {
            throw new IllegalStateException(sent.getError().getMessage());
        }
        return sent.getTransactionHash();
    }
    private Credentials credentials(AccountChainProfile profile, ChainAddressRecord from) {
        ECKey ecKey = keyService.key(profile, from);
        return Credentials.create(Numeric.toHexStringNoPrefixZeroPadded(ecKey.getPrivKey(), 64));
    }
    private BigInteger gasPrice(Web3j web3j) throws Exception {
        return web3j.ethGasPrice().send().getGasPrice();
    }
    private BigInteger pendingNonce(Web3j web3j, String address) throws Exception {
        return web3j.ethGetTransactionCount(address, DefaultBlockParameterName.PENDING)
                .send()
                .getTransactionCount();
    }

    private void record(String chain, String hash, String from, String to, String symbol, String contract,
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
            Web3j web3j = Web3j.build(new HttpService(node.getRpcUrl()));
            try {
                return request.apply(web3j);
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
                .orElseThrow(() -> new IllegalStateException("missing enabled chain_profile for " + chain));
    }
    private String nativeSymbol(AccountChainProfile profile) {
        return profile.getNativeSymbol() == null ? profile.getChain() : profile.getNativeSymbol();
    }
    private BigDecimal fee(BigInteger gasPrice, BigInteger gasLimit) {
        return weiToNative(gasPrice.multiply(gasLimit));
    }
    private BigDecimal weiToNative(BigInteger wei) {
        return new BigDecimal(wei).divide(new BigDecimal(WEI_PER_NATIVE), 18, RoundingMode.DOWN);
    }
    private String normalize(String address) {
        return address == null ? null : address.toLowerCase(java.util.Locale.ROOT);
    }

    @FunctionalInterface
    private interface Web3Request<T> {
        T apply(Web3j web3j) throws Exception;
    }
}
