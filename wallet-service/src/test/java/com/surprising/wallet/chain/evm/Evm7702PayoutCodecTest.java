package com.surprising.wallet.chain.evm;

import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Evm7702PayoutCodecTest {
    private static final Credentials AUTHORITY = Credentials.create(
            "0x59c6995e998f97a5a0044966f0945389dc9e86dae88c7a8412f4603b6b78690d");
    private static final String EXECUTOR = "0xf39fd6e51aad88f6f4ce6ab8827279cfffb92266";
    private static final String TOKEN = "0x3333333333333333333333333333333333333333";
    private static final String RECIPIENT = "0x4444444444444444444444444444444444444444";

    @Test
    void signsRecoverableRequestAndEncodesExactPayoutSelector() throws SignatureException {
        byte[] batchId = Hash.sha3("tenant:WITHDRAWAL:batch".getBytes(StandardCharsets.UTF_8));
        Evm7702PayoutItem item = item(0, TOKEN, RECIPIENT, 25_000_000L);
        Evm7702PayoutRequest request = new Evm7702PayoutRequest(
                batchId, AUTHORITY.getAddress(), EXECUTOR, List.of(item),
                BigInteger.valueOf(7), BigInteger.valueOf(2_000_000_000L));
        Evm7702PayoutSigner signer = new Evm7702PayoutSigner();
        byte[] signature = signer.sign(BigInteger.valueOf(31337), request, AUTHORITY);
        Sign.SignatureData data = new Sign.SignatureData(
                signature[64], java.util.Arrays.copyOfRange(signature, 0, 32),
                java.util.Arrays.copyOfRange(signature, 32, 64));
        BigInteger recovered = Sign.signedMessageHashToKey(
                signer.digest(BigInteger.valueOf(31337), request), data);
        assertEquals(AUTHORITY.getAddress(), "0x" + Keys.getAddress(recovered));

        String calldata = new Evm7702PayoutCodec().encode(request, signature);
        String selector = Hash.sha3String(
                "payoutBatch(bytes32,(bytes32,uint256,address,address,uint256,uint256)[],uint256,uint256,bytes)")
                .substring(0, 10);
        assertTrue(calldata.startsWith(selector));
    }

    @Test
    void rejectsDuplicateWithdrawalIdentityBeforeSigning() {
        Evm7702PayoutItem first = item(0, TOKEN, RECIPIENT, 1);
        Evm7702PayoutItem duplicate = new Evm7702PayoutItem(
                first.withdrawalId(), BigInteger.ONE, TOKEN, RECIPIENT,
                BigInteger.TWO, BigInteger.valueOf(120_000));
        assertThrows(IllegalArgumentException.class, () -> new Evm7702PayoutRequest(
                Hash.sha3("batch".getBytes(StandardCharsets.UTF_8)), AUTHORITY.getAddress(),
                EXECUTOR, List.of(first, duplicate), BigInteger.ZERO,
                BigInteger.valueOf(2_000_000_000L)));
    }

    @Test
    void parsesNativeSuccessAndTokenFailureWithoutSettlingFailedTransfer() {
        byte[] batchId = Hash.sha3("receipt-batch".getBytes(StandardCharsets.UTF_8));
        Evm7702PayoutItem nativeItem = item(
                0, "0x0000000000000000000000000000000000000000", RECIPIENT, 10);
        Evm7702PayoutItem tokenItem = item(
                1, TOKEN, "0x5555555555555555555555555555555555555555", 20);
        List<Log> logs = new ArrayList<>();
        logs.add(itemLog(batchId, nativeItem, true, BigInteger.TEN, 0));
        logs.add(itemLog(batchId, tokenItem, false, BigInteger.ZERO, 1));
        logs.add(batchLog(batchId, 2, 1, 1, 2));
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setStatus("0x1");
        receipt.setTo(AUTHORITY.getAddress());
        receipt.setLogs(logs);

        Evm7702PayoutReceiptParser.ParsedReceipt parsed =
                new Evm7702PayoutReceiptParser().parse(
                        receipt, AUTHORITY.getAddress(), batchId, List.of(
                                expected(nativeItem), expected(tokenItem)));
        assertTrue(parsed.items().getFirst().success());
        assertFalse(parsed.items().get(1).success());
        assertEquals(1, parsed.batch().successCount());
        assertEquals(1, parsed.batch().failureCount());
        assertArrayEquals(tokenItem.withdrawalId(), parsed.items().get(1).withdrawalId());
    }

    private static Evm7702PayoutItem item(int index, String token, String recipient, long amount) {
        return new Evm7702PayoutItem(
                Hash.sha3(("withdrawal-" + index).getBytes(StandardCharsets.UTF_8)),
                BigInteger.valueOf(index), token, recipient, BigInteger.valueOf(amount),
                BigInteger.valueOf(120_000));
    }

    private static Evm7702PayoutReceiptParser.ExpectedPayout expected(Evm7702PayoutItem item) {
        return new Evm7702PayoutReceiptParser.ExpectedPayout(
                item.withdrawalId(), item.token(), item.recipient(), item.amount());
    }

    private static Log itemLog(byte[] batchId, Evm7702PayoutItem item, boolean success,
                               BigInteger actual, int logIndex) {
        byte[] error = success ? new byte[32] : Hash.sha3("transfer-failed".getBytes(StandardCharsets.UTF_8));
        Log log = new Log();
        log.setAddress(AUTHORITY.getAddress());
        log.setLogIndex(Numeric.encodeQuantity(BigInteger.valueOf(logIndex)));
        log.setTopics(List.of(
                Evm7702PayoutReceiptParser.ITEM_TOPIC,
                Numeric.toHexString(batchId),
                Numeric.toHexStringWithPrefixZeroPadded(item.itemIndex(), 64),
                Numeric.toHexString(item.withdrawalId())));
        log.setData(FunctionEncoder.encodeConstructor(List.<Type>of(
                new Address(item.token()), new Address(item.recipient()),
                new Uint256(item.amount()), new Uint256(actual), new Bool(success),
                new Bytes32(error))));
        return log;
    }

    private static Log batchLog(byte[] batchId, int total, int succeeded,
                                int failed, int logIndex) {
        Log log = new Log();
        log.setAddress(AUTHORITY.getAddress());
        log.setLogIndex(Numeric.encodeQuantity(BigInteger.valueOf(logIndex)));
        log.setTopics(List.of(
                Evm7702PayoutReceiptParser.BATCH_TOPIC,
                Numeric.toHexString(batchId), addressTopic(AUTHORITY.getAddress())));
        log.setData(FunctionEncoder.encodeConstructor(List.<Type>of(
                new Uint256(total), new Uint256(succeeded), new Uint256(failed))));
        return log;
    }

    private static String addressTopic(String address) {
        return "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(address).toLowerCase();
    }
}
