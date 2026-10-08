package com.surprising.wallet.service;

import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.pojo.*;
import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainJdbcRepository;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RbfBumpServiceTest {
    @Test void withdrawalRbfUsesFinalRawSignatureAndPreservesUserAmountAndInputLocks() {
        var json = new ObjectMapper(); var repository = mock(ChainJdbcRepository.class);
        var runtime = mock(BlockchainRuntimeService.class); var switches = mock(WalletRuntimeConfigService.class);
        var rates = mock(ChainFeeRateService.class);
        var btc = AssetRuntimeMetadata.fromProfile(1, "BTC", "BTC", 1, 0, 8, null);
        UUID tenant = UUID.randomUUID(); String hash = "a".repeat(64), input = "c".repeat(64);
        var signature = json.createObjectNode().put("tenantId", tenant.toString())
                .put("signingRequestId", UUID.randomUUID().toString()).put("feeRate", 10)
                .put("fee", 2020).put("rawTransaction", "00").put("txId", hash).put("changeAddress", "hot");
        signature.set("utxos", json.valueToTree(List.of(UtxoTransaction.builder().txId(input)
                .seq((short) 0).balance(new BigDecimal("0.01")).build())));
        signature.set("withdraw", json.valueToTree(List.of(WithdrawRecord.builder().withdrawId("WD-1")
                .address("external").balance(new BigDecimal("0.005")).fee(new BigDecimal("0.0001")).build())));
        var tx = WithdrawTransaction.builder().id(42).status(Constants.SENT).txId(hash)
                .balance(new BigDecimal("0.01")).signature(JacksonJson.writeValue(json, signature)).build();
        when(runtime.assetMetadata("BTC")).thenReturn(btc);
        when(repository.lockBitcoinLikeSigningTransaction(btc, 42)).thenReturn(Optional.of(tx));
        when(repository.isUtxoLockedBy("BTC", input, 0, "42")).thenReturn(true);
        when(repository.updateBitcoinLikeSigningTransaction(btc, tx)).thenReturn(1);
        when(switches.isTaskEnabled("BTC", WalletRuntimeConfigService.TASK_WITHDRAW)).thenReturn(true);
        when(rates.get("BTC")).thenReturn("10");
        var service = new RbfBumpService(repository, runtime, switches, mock(QueueWorker.class), rates, json);
        var result = JacksonJson.readValue(json, service.bumpFee(42, tenant, hash), WithdrawTransaction.class);
        var updated = JacksonJson.readObject(json, result.getSignature());
        assertEquals(JacksonJson.readObject(json, JacksonJson.writeValue(json, signature)).path("withdraw"), updated.path("withdraw"));
        assertEquals(20, updated.path("feeRate").asLong()); assertEquals(1, updated.path("rbfHistory").size());
        assertFalse(updated.has("rawTransaction")); assertFalse(updated.has("firstSignTx"));
        assertNotEquals(signature.path("signingRequestId"), updated.path("signingRequestId"));
        verify(repository, never()).releaseUtxos(anyString(), anyString());
        verify(repository, never()).lockUtxo(anyString(), anyString(), anyInt(), anyString());
        verify(repository, never()).freezeLedgerBalance(any(), anyString(), anyString(), anyString(), any());
        verify(repository).updateWithdrawalStatus(tenant, "BTC", "WD-1", "SIGNING", null, null, null);
    }
}
