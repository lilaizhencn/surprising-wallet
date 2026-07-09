package com.surprising.wallet.service.chain.aptos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.surprising.wallet.common.key.Ed25519DerivedKey;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Component
@RequiredArgsConstructor
public class AptosTransactionSigner {
    private static final byte[] RAW_TRANSACTION_PREFIX = sha3("APTOS::RawTransaction".getBytes());
    private static final String APTOS_ACCOUNT_MODULE = "0x1::aptos_account";
    private static final HexFormat HEX = HexFormat.of();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AptosKeyService keyService;

    public SignedTransaction nativeTransfer(long derivationIndex, String sender, long sequenceNumber,
                                            String recipient, long amountOctas, long maxGasAmount,
                                            long gasUnitPrice, int chainId) {
        return nativeTransfer(0L, 0, derivationIndex, sender, sequenceNumber,
                recipient, amountOctas, maxGasAmount, gasUnitPrice, chainId);
    }

    public SignedTransaction nativeTransfer(long userId, int biz, long derivationIndex,
                                            String sender, long sequenceNumber,
                                            String recipient, long amountOctas, long maxGasAmount,
                                            long gasUnitPrice, int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber,
                APTOS_ACCOUNT_MODULE, "transfer", List.of(),
                List.of(FunctionArgument.address(recipient), FunctionArgument.u64(amountOctas)),
                maxGasAmount, gasUnitPrice, chainId);
    }

    public SignedTransaction coinTransfer(long derivationIndex, String sender, long sequenceNumber,
                                          String coinType, String recipient, long amountAtomic,
                                          long maxGasAmount, long gasUnitPrice, int chainId) {
        return coinTransfer(0L, 0, derivationIndex, sender, sequenceNumber,
                coinType, recipient, amountAtomic, maxGasAmount, gasUnitPrice, chainId);
    }

    public SignedTransaction coinTransfer(long userId, int biz, long derivationIndex,
                                          String sender, long sequenceNumber,
                                          String coinType, String recipient, long amountAtomic,
                                          long maxGasAmount, long gasUnitPrice, int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber,
                APTOS_ACCOUNT_MODULE, "transfer_coins", List.of(coinType),
                List.of(FunctionArgument.address(recipient), FunctionArgument.u64(amountAtomic)),
                maxGasAmount, gasUnitPrice, chainId);
    }

    public SignedTransaction managedCoinRegister(long derivationIndex, String sender, long sequenceNumber,
                                                 String coinType, long maxGasAmount,
                                                 long gasUnitPrice, int chainId) {
        return managedCoinRegister(0L, 0, derivationIndex, sender, sequenceNumber,
                coinType, maxGasAmount, gasUnitPrice, chainId);
    }

    public SignedTransaction managedCoinRegister(long userId, int biz, long derivationIndex,
                                                 String sender, long sequenceNumber,
                                                 String coinType, long maxGasAmount,
                                                 long gasUnitPrice, int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber,
                "0x1::managed_coin", "register", List.of(coinType), List.of(),
                maxGasAmount, gasUnitPrice, chainId);
    }

    public SignedTransaction entryFunction(long derivationIndex, String sender, long sequenceNumber,
                                           String module, String function, List<String> typeArguments,
                                           List<FunctionArgument> arguments, long maxGasAmount,
                                           long gasUnitPrice, int chainId) {
        return entryFunction(0L, 0, derivationIndex, sender, sequenceNumber,
                module, function, typeArguments, arguments, maxGasAmount, gasUnitPrice, chainId);
    }

    public SignedTransaction entryFunction(long userId, int biz, long derivationIndex,
                                           String sender, long sequenceNumber,
                                           String module, String function, List<String> typeArguments,
                                           List<FunctionArgument> arguments, long maxGasAmount,
                                           long gasUnitPrice, int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber, module, function, typeArguments,
                arguments, maxGasAmount, gasUnitPrice, chainId);
    }

    public SignedTransaction publishPackage(long userId, int biz, long derivationIndex,
                                            String sender, long sequenceNumber,
                                            byte[] metadata,
                                            List<byte[]> modules,
                                            long maxGasAmount,
                                            long gasUnitPrice,
                                            int chainId) {
        return sign(userId, biz, derivationIndex, sender, sequenceNumber,
                "0x1::code", "publish_package_txn", List.of(),
                List.of(FunctionArgument.u8Vector(metadata), FunctionArgument.u8VectorVector(modules)),
                maxGasAmount, gasUnitPrice, chainId);
    }

    private SignedTransaction sign(long userId, int biz, long derivationIndex,
                                   String sender, long sequenceNumber,
                                   String module, String function, List<String> typeArguments,
                                   List<FunctionArgument> arguments, long maxGasAmount, long gasUnitPrice,
                                   int chainId) {
        long expiration = Instant.now().plusSeconds(600).getEpochSecond();
        AptosBcs.EntryFunctionPayload payload = new AptosBcs.EntryFunctionPayload(
                module, function, typeArguments, arguments.stream().map(FunctionArgument::bcs).toList());
        byte[] raw = AptosBcs.rawEntryFunctionTransaction(sender, sequenceNumber, payload,
                maxGasAmount, gasUnitPrice, expiration, chainId);
        byte[] signingMessage = signingMessage(raw);
        byte[] signature = keyService.sign(userId, biz, derivationIndex, signingMessage);
        Ed25519DerivedKey key = keyService.derive(userId, biz, derivationIndex);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("sender", AptosHex.normalizeAddress(sender));
        request.put("sequence_number", Long.toUnsignedString(sequenceNumber));
        request.put("max_gas_amount", Long.toUnsignedString(maxGasAmount));
        request.put("gas_unit_price", Long.toUnsignedString(gasUnitPrice));
        request.put("expiration_timestamp_secs", Long.toUnsignedString(expiration));
        request.set("payload", jsonPayload(module, function, typeArguments, arguments));
        ObjectNode sig = objectMapper.createObjectNode();
        sig.put("type", "ed25519_signature");
        sig.put("public_key", AptosHex.withPrefix(key.publicKey()));
        sig.put("signature", AptosHex.withPrefix(signature));
        request.set("signature", sig);
        return new SignedTransaction(sequenceNumber, maxGasAmount, gasUnitPrice, expiration, raw, request);
    }

    private ObjectNode jsonPayload(String module, String function, List<String> typeArguments,
                                   List<FunctionArgument> arguments) {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "entry_function_payload");
        payload.put("function", module + "::" + function);
        ArrayNode typeArgs = objectMapper.createArrayNode();
        typeArguments.forEach(typeArgs::add);
        payload.set("type_arguments", typeArgs);
        ArrayNode args = objectMapper.createArrayNode();
        arguments.forEach(argument -> args.add(objectMapper.valueToTree(argument.jsonValue())));
        payload.set("arguments", args);
        return payload;
    }

    private static byte[] signingMessage(byte[] rawTransaction) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(RAW_TRANSACTION_PREFIX, 0, RAW_TRANSACTION_PREFIX.length);
        out.write(rawTransaction, 0, rawTransaction.length);
        return out.toByteArray();
    }

    private static byte[] sha3(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA3-256").digest(value);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA3-256 is required for Aptos signing", e);
        }
    }

    public record SignedTransaction(long sequenceNumber, long maxGasAmount, long gasUnitPrice,
                                    long expirationTimestampSecs, byte[] rawTransaction,
                                    ObjectNode json) {
    }

    public record FunctionArgument(byte[] bcs, Object jsonValue) {
        public FunctionArgument {
            Objects.requireNonNull(bcs, "bcs");
            Objects.requireNonNull(jsonValue, "jsonValue");
        }

        public static FunctionArgument address(String address) {
            return new FunctionArgument(AptosBcs.addressArg(address), AptosHex.normalizeAddress(address));
        }

        public static FunctionArgument u64(long value) {
            return new FunctionArgument(AptosBcs.u64Arg(value), Long.toUnsignedString(value));
        }

        public static FunctionArgument u8Vector(byte[] value) {
            return new FunctionArgument(AptosBcs.u8VectorArg(value), "0x" + HEX.formatHex(value));
        }

        public static FunctionArgument u8VectorVector(List<byte[]> values) {
            List<String> json = values.stream()
                    .map(bytes -> "0x" + HEX.formatHex(bytes))
                    .toList();
            return new FunctionArgument(AptosBcs.u8VectorVectorArg(values), json);
        }
    }
}
