package com.surprising.wallet.jobs.custody;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.chain.evm.EvmAccountTransactionService;
import com.surprising.wallet.service.config.ChainRpcNodeService;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

@Component
public class EvmAssetRecoveryChainGateway implements CustodyAssetRecoveryChainGateway {
    private static final BigDecimal WEI = new BigDecimal("1000000000000000000");
    private static final String TRANSFER_TOPIC =
            "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private static final String DECIMALS_SELECTOR = "0x313ce567";

    private final ChainJdbcRepository repository;
    private final ChainRpcNodeService rpcNodes;
    private final EvmAccountTransactionService transactions;
    private final ObjectMapper objectMapper;

    public EvmAssetRecoveryChainGateway(ChainJdbcRepository repository,
                                        ChainRpcNodeService rpcNodes,
                                        EvmAccountTransactionService transactions,
                                        ObjectMapper objectMapper) {
        this.repository = repository;
        this.rpcNodes = rpcNodes;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(String chain) {
        try {
            return ChainType.valueOf(chain).isEvm();
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public Verification verify(VerificationRequest request) {
        AccountChainProfile profile = profile(request.chain());
        return withWeb3(profile, web3j -> verify(web3j, profile, request));
    }

    @Override
    public String execute(ExecutionRequest request) {
        if (request.tokenContract() == null) {
            return transactions.sendNative(
                    request.chain(), request.source(), request.recoveryAddress(), request.amount());
        }
        TokenDefinition token = TokenDefinition.builder()
                .chain(request.chain())
                .symbol(request.assetSymbol())
                .contractAddress(request.tokenContract())
                .decimals(request.tokenDecimals())
                .standard("ERC20")
                .nativeAsset(false)
                .active(true)
                .build();
        return transactions.sendToken(
                request.chain(), request.source(), token, request.recoveryAddress(), request.amount());
    }

    @Override
    public boolean confirmed(String chain, String txHash) {
        AccountChainProfile profile = profile(chain);
        return withWeb3(profile, web3j -> {
            TransactionReceipt receipt = web3j.ethGetTransactionReceipt(txHash).send()
                    .getTransactionReceipt().orElse(null);
            if (receipt == null || receipt.getBlockNumber() == null) {
                return false;
            }
            if (!receipt.isStatusOK()) {
                throw new PermanentlyFailedTransactionException(
                        "recovery transaction failed on chain");
            }
            EthBlock.Block canonical = web3j.ethGetBlockByNumber(
                    org.web3j.protocol.core.DefaultBlockParameter.valueOf(receipt.getBlockNumber()),
                    false).send().getBlock();
            if (canonical == null || !receipt.getBlockHash().equalsIgnoreCase(canonical.getHash())) {
                return false;
            }
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger confirmations = latest.subtract(receipt.getBlockNumber()).add(BigInteger.ONE);
            return confirmations.compareTo(BigInteger.valueOf(
                    Math.max(1, profile.getWithdrawConfirmations()))) >= 0;
        });
    }

    private Verification verify(Web3j web3j, AccountChainProfile profile,
                                VerificationRequest request) throws Exception {
        TransactionReceipt receipt = web3j.ethGetTransactionReceipt(request.txHash()).send()
                .getTransactionReceipt()
                .orElseThrow(() -> new IllegalArgumentException("transaction receipt was not found"));
        if (!receipt.isStatusOK() || receipt.getBlockNumber() == null || receipt.getBlockHash() == null) {
            throw new IllegalArgumentException("transaction is not successfully confirmed");
        }
        EthBlock.Block canonical = web3j.ethGetBlockByNumber(
                org.web3j.protocol.core.DefaultBlockParameter.valueOf(receipt.getBlockNumber()),
                false).send().getBlock();
        if (canonical == null || !receipt.getBlockHash().equalsIgnoreCase(canonical.getHash())) {
            throw new IllegalArgumentException("transaction block is not canonical");
        }
        BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
        int confirmations = latest.subtract(receipt.getBlockNumber()).add(BigInteger.ONE)
                .max(BigInteger.ZERO).min(BigInteger.valueOf(Integer.MAX_VALUE)).intValue();
        int required = Math.max(1, profile.getDepositConfirmations());
        if (confirmations < required) {
            throw new IllegalArgumentException(
                    "transaction has " + confirmations + " confirmations; " + required + " required");
        }

        String destination = normalizeAddress(request.destinationAddress());
        BigDecimal amount;
        long logIndex;
        Integer tokenDecimals = null;
        String tokenContract = normalizeNullableAddress(request.tokenContract());
        if (tokenContract == null) {
            org.web3j.protocol.core.methods.response.Transaction tx =
                    web3j.ethGetTransactionByHash(request.txHash()).send().getTransaction()
                            .orElseThrow(() -> new IllegalArgumentException("transaction was not found"));
            if (!destination.equals(normalizeAddress(tx.getTo())) || tx.getValue().signum() <= 0) {
                throw new IllegalArgumentException("transaction does not transfer native funds to this address");
            }
            amount = new BigDecimal(tx.getValue()).divide(WEI);
            logIndex = 0L;
        } else {
            tokenDecimals = tokenDecimals(web3j, request.chain(), tokenContract);
            Log transfer = matchingTransfer(
                    receipt.getLogs(), tokenContract, destination, request.requestedLogIndex());
            BigInteger atomicAmount = Numeric.toBigInt(transfer.getData());
            if (atomicAmount.signum() <= 0) {
                throw new IllegalArgumentException("token transfer amount must be positive");
            }
            amount = new BigDecimal(atomicAmount).movePointLeft(tokenDecimals);
            logIndex = transfer.getLogIndex().longValueExact();
        }
        if (request.claimedAmount() != null
                && request.claimedAmount().compareTo(amount) != 0) {
            throw new IllegalArgumentException(
                    "claimed amount does not match the on-chain transfer amount");
        }
        BigDecimal nativeBalance = new BigDecimal(web3j.ethGetBalance(
                        destination, DefaultBlockParameterName.LATEST).send().getBalance())
                .divide(WEI);
        LinkedHashMap<String, Object> details = new LinkedHashMap<>();
        details.put("network", profile.getNetwork());
        details.put("destinationAddress", destination);
        details.put("nativeBalanceForGas", nativeBalance);
        details.put("requiredConfirmations", required);
        details.put("canonical", true);
        return new Verification(tokenContract, tokenDecimals, logIndex, amount,
                receipt.getBlockNumber().longValueExact(), receipt.getBlockHash(), confirmations,
                json(details));
    }

    private Log matchingTransfer(List<Log> logs, String tokenContract, String destination,
                                 Long requestedLogIndex) {
        List<Log> matches = logs.stream()
                .filter(log -> tokenContract.equals(normalizeNullableAddress(log.getAddress())))
                .filter(log -> log.getTopics() != null && log.getTopics().size() >= 3)
                .filter(log -> TRANSFER_TOPIC.equalsIgnoreCase(log.getTopics().getFirst()))
                .filter(log -> destination.equals(topicAddress(log.getTopics().get(2))))
                .filter(log -> requestedLogIndex == null
                        || (log.getLogIndex() != null
                        && requestedLogIndex == log.getLogIndex().longValue()))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("matching ERC-20 transfer log was not found");
        }
        if (matches.size() > 1 && requestedLogIndex == null) {
            throw new IllegalArgumentException("multiple matching transfers found; logIndex is required");
        }
        return matches.getFirst();
    }

    private int tokenDecimals(Web3j web3j, String chain, String contract) throws Exception {
        TokenDefinition configured = repository.findTokenByContract(chain, contract).orElse(null);
        if (configured != null && configured.getDecimals() != null) {
            return configured.getDecimals();
        }
        String value = web3j.ethCall(
                        Transaction.createEthCallTransaction(null, contract, DECIMALS_SELECTOR),
                        DefaultBlockParameterName.LATEST)
                .send().getValue();
        int decimals = Numeric.toBigInt(value).intValueExact();
        if (decimals < 0 || decimals > 36) {
            throw new IllegalArgumentException("token decimals are outside the supported range");
        }
        return decimals;
    }

    private AccountChainProfile profile(String chain) {
        AccountChainProfile profile = repository.findProfileByChain(chain)
                .orElseThrow(() -> new IllegalArgumentException("actual chain is not enabled by the platform"));
        if (!"evm".equalsIgnoreCase(profile.getFamily())) {
            throw new IllegalArgumentException("automatic recovery currently supports EVM chains");
        }
        return profile;
    }

    private <T> T withWeb3(AccountChainProfile profile, Web3Request<T> request) {
        return rpcNodes.withFailover(profile.getChain(), profile.getNetwork(), node -> {
            Web3j web3j = Web3j.build(new HttpService(node.getRpcUrl()));
            try {
                return request.apply(web3j);
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e.getMessage(), e);
            } finally {
                web3j.shutdown();
            }
        });
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize recovery verification", e);
        }
    }

    private static String topicAddress(String topic) {
        String clean = Numeric.cleanHexPrefix(topic);
        return "0x" + clean.substring(clean.length() - 40).toLowerCase(Locale.ROOT);
    }

    private static String normalizeAddress(String address) {
        String value = normalizeNullableAddress(address);
        if (value == null) {
            throw new IllegalArgumentException("valid EVM address is required");
        }
        return value;
    }

    private static String normalizeNullableAddress(String address) {
        String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        return value.matches("^0x[0-9a-f]{40}$") ? value : null;
    }

    @FunctionalInterface
    private interface Web3Request<T> {
        T apply(Web3j web3j) throws Exception;
    }
}
