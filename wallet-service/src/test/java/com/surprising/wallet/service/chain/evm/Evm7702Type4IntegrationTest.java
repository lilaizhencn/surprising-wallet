package com.surprising.wallet.service.chain.evm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.AuthorizationTuple;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.RawTransaction;
import org.web3j.crypto.TransactionEncoder;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real local-chain test: broadcasts a Web3j-encoded type-4 transaction with three
 * EIP-7702 authorizations. No hardhat_setCode shortcut is used here.
 */
class Evm7702Type4IntegrationTest {
    private static final String RPC = "http://127.0.0.1:8545";
    private static final Credentials RELAYER = Credentials.create(
            "0xac0974bec39a17e36ba4a6b4d238ff944bacb478cbed5efcae784d7bf4f2ff80");
    private static final BigInteger GAS_PRICE = BigInteger.valueOf(2_000_000_000L);

    @Test
    void shouldAuthorizeAndCollectThreeZeroEthAddressesWithOneType4Tx() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("evm.7702.enabled"),
                "set -Devm.7702.enabled=true and start the Hardhat Prague node");
        String collector = requiredProperty("evm.7702.collector");
        String delegate = requiredProperty("evm.7702.delegate");
        long chainId = Long.parseLong(System.getProperty("evm.7702.chain-id", "31337"));

        Web3j web3j = Web3j.build(new HttpService(RPC));
        try {
            assertEquals(BigInteger.valueOf(chainId), web3j.ethChainId().send().getChainId());
            assertFalse(web3j.ethGetCode(collector, DefaultBlockParameterName.LATEST).send().getCode().equals("0x"));
            assertFalse(web3j.ethGetCode(delegate, DefaultBlockParameterName.LATEST).send().getCode().equals("0x"));

            BigInteger relayerNonce = web3j.ethGetTransactionCount(
                    RELAYER.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
            Deployment token = deployMockToken(web3j, relayerNonce, chainId);
            relayerNonce = relayerNonce.add(BigInteger.ONE);

            List<Credentials> authorities = List.of(
                    randomCredentials(), randomCredentials(), randomCredentials());
            List<BigInteger> amounts = List.of(
                    BigInteger.valueOf(11_000_000L),
                    BigInteger.valueOf(22_000_000L),
                    BigInteger.valueOf(33_000_000L));
            for (int i = 0; i < authorities.size(); i++) {
                TransactionReceipt mint = sendLegacyCall(
                        web3j, relayerNonce, token.address(),
                        encodeMint(authorities.get(i).getAddress(), amounts.get(i)), chainId);
                assertEquals("0x1", mint.getStatus());
                relayerNonce = relayerNonce.add(BigInteger.ONE);
                assertEquals(BigInteger.ZERO, web3j.ethGetBalance(
                        authorities.get(i).getAddress(), DefaultBlockParameterName.LATEST).send().getBalance());
                assertEquals("0x", web3j.ethGetCode(
                        authorities.get(i).getAddress(), DefaultBlockParameterName.LATEST).send().getCode());
            }

            byte[] batchId = Hash.sha3("tenant-a:real-type4-batch".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            BigInteger deadline = BigInteger.valueOf(Instant.now().plus(Duration.ofMinutes(10)).getEpochSecond());
            Evm7702OperationSigner operationSigner = new Evm7702OperationSigner();
            Evm7702AuthorizationService authorizationService = new Evm7702AuthorizationService();
            List<Evm7702CollectionRequest> requests = new ArrayList<>();
            List<byte[]> signatures = new ArrayList<>();
            List<AuthorizationTuple> authorizations = new ArrayList<>();
            for (int i = 0; i < authorities.size(); i++) {
                Credentials authority = authorities.get(i);
                Evm7702CollectionRequest request = new Evm7702CollectionRequest(
                        batchId, BigInteger.valueOf(i), authority.getAddress(), collector,
                        token.address(), RELAYER.getAddress(), amounts.get(i), BigInteger.ZERO,
                        deadline, BigInteger.valueOf(180_000L));
                requests.add(request);
                signatures.add(operationSigner.sign(BigInteger.valueOf(chainId), request, authority));
                BigInteger authorityNonce = web3j.ethGetTransactionCount(
                        authority.getAddress(), DefaultBlockParameterName.PENDING).send().getTransactionCount();
                assertEquals(BigInteger.ZERO, authorityNonce);
                authorizations.add(authorizationService.authorize(
                        BigInteger.valueOf(chainId), delegate, authorityNonce, authority));
            }

            String calldata = new Evm7702ContractCodec().encodeCollectBatch(requests, signatures);
            Evm7702BatchTransactionService.SignedType4Transaction signed =
                    new Evm7702BatchTransactionService().sign(
                            chainId, relayerNonce, BigInteger.valueOf(1_000_000_000L),
                            BigInteger.valueOf(4_000_000_000L), BigInteger.valueOf(1_500_000L),
                            collector, calldata, authorizations, RELAYER);
            EthSendTransaction response = web3j.ethSendRawTransaction(signed.rawTransaction()).send();
            assertFalse(response.hasError(), response.hasError() ? response.getError().getMessage() : "");
            assertEquals(signed.transactionHash(), response.getTransactionHash());
            TransactionReceipt receipt = waitReceipt(web3j, signed.transactionHash());
            assertEquals("0x1", receipt.getStatus());
            assertEquals(collector.toLowerCase(), receipt.getTo().toLowerCase());
            assertTrue(receipt.getGasUsed().signum() > 0);

            BigInteger expectedTotal = amounts.stream().reduce(BigInteger.ZERO, BigInteger::add);
            assertEquals(expectedTotal, tokenBalance(web3j, token.address(), RELAYER.getAddress()));
            for (Credentials authority : authorities) {
                assertEquals(BigInteger.ZERO, tokenBalance(web3j, token.address(), authority.getAddress()));
                assertEquals(BigInteger.ZERO, web3j.ethGetBalance(
                        authority.getAddress(), DefaultBlockParameterName.LATEST).send().getBalance());
                assertEquals("0xef0100" + delegate.substring(2).toLowerCase(), web3j.ethGetCode(
                        authority.getAddress(), DefaultBlockParameterName.LATEST).send().getCode().toLowerCase());
                assertEquals(BigInteger.ONE, operationNonce(web3j, authority.getAddress()));
            }
            long itemEvents = receipt.getLogs().stream()
                    .filter(log -> log.getAddress().equalsIgnoreCase(collector))
                    .filter(log -> !log.getTopics().isEmpty())
                    .filter(log -> log.getTopics().getFirst().equalsIgnoreCase(Hash.sha3String(
                            "CollectionItemResult(bytes32,uint256,address,address,address,uint256,uint256,bool,bytes32)")))
                    .count();
            assertEquals(3L, itemEvents);
        } finally {
            web3j.shutdown();
        }
    }

    private static Deployment deployMockToken(
            Web3j web3j, BigInteger nonce, long chainId) throws Exception {
        Path artifact = projectRoot().resolve(
                "resources/infra/evm-fork/artifacts/contracts/MockERC20.sol/MockERC20.json");
        JsonNode json = new ObjectMapper().readTree(Files.readString(artifact));
        String bytecode = json.path("bytecode").asText();
        assertTrue(bytecode.startsWith("0x") && bytecode.length() > 100);
        String constructor = FunctionEncoder.encodeConstructor(List.of(
                new Utf8String("Test USD"), new Utf8String("TUSD"), new Uint8(6)));
        RawTransaction raw = RawTransaction.createContractTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(5_000_000L), BigInteger.ZERO,
                bytecode + Numeric.cleanHexPrefix(constructor));
        byte[] signed = TransactionEncoder.signMessage(raw, chainId, RELAYER);
        EthSendTransaction response = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        assertFalse(response.hasError(), response.hasError() ? response.getError().getMessage() : "");
        TransactionReceipt receipt = waitReceipt(web3j, response.getTransactionHash());
        assertEquals("0x1", receipt.getStatus());
        assertNotNull(receipt.getContractAddress());
        return new Deployment(receipt.getContractAddress());
    }

    private static TransactionReceipt sendLegacyCall(
            Web3j web3j, BigInteger nonce, String to, String data, long chainId)
            throws Exception {
        RawTransaction raw = RawTransaction.createTransaction(
                nonce, GAS_PRICE, BigInteger.valueOf(500_000L), to, BigInteger.ZERO, data);
        byte[] signed = TransactionEncoder.signMessage(raw, chainId, RELAYER);
        EthSendTransaction response = web3j.ethSendRawTransaction(Numeric.toHexString(signed)).send();
        assertFalse(response.hasError(), response.hasError() ? response.getError().getMessage() : "");
        return waitReceipt(web3j, response.getTransactionHash());
    }

    private static String encodeMint(String recipient, BigInteger amount) {
        return FunctionEncoder.encode(new Function(
                "mint", List.of(new Address(recipient), new Uint256(amount)), List.of()));
    }

    private static BigInteger tokenBalance(Web3j web3j, String token, String owner) throws Exception {
        Function function = new Function(
                "balanceOf", List.of(new Address(owner)), List.of(new TypeReference<Uint256>() { }));
        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(RELAYER.getAddress(), token, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        assertFalse(response.hasError(), response.hasError() ? response.getError().getMessage() : "");
        List<Type> values = FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
        return (BigInteger) values.getFirst().getValue();
    }

    private static BigInteger operationNonce(Web3j web3j, String authority) throws Exception {
        Function function = new Function("operationNonce", List.of(), List.of(new TypeReference<Uint256>() { }));
        EthCall response = web3j.ethCall(
                Transaction.createEthCallTransaction(RELAYER.getAddress(), authority, FunctionEncoder.encode(function)),
                DefaultBlockParameterName.LATEST).send();
        assertFalse(response.hasError(), response.hasError() ? response.getError().getMessage() : "");
        return (BigInteger) FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters())
                .getFirst().getValue();
    }

    private static TransactionReceipt waitReceipt(Web3j web3j, String txHash) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        while (Instant.now().isBefore(deadline)) {
            Optional<TransactionReceipt> receipt = web3j.ethGetTransactionReceipt(txHash).send().getTransactionReceipt();
            if (receipt.isPresent()) {
                return receipt.get();
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("timed out waiting for transaction receipt " + txHash);
    }

    private static Credentials randomCredentials() throws Exception {
        return Credentials.create(Keys.createEcKeyPair());
    }

    private static String requiredProperty(String name) {
        String value = System.getProperty(name, "").trim();
        if (!value.matches("^0x[0-9a-fA-F]{40}$")) {
            throw new IllegalArgumentException("missing valid -D" + name);
        }
        return Keys.toChecksumAddress(value);
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) && Files.isDirectory(current.resolve("resources/infra/evm-fork"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("cannot locate project root");
    }

    private record Deployment(String address) {
    }
}
