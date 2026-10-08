package com.surprising.wallet.service;

import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.dto.TransactionDTO;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.common.pojo.WithdrawTransaction;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainJdbcRepository;
import com.surprising.wallet.repository.WalletOutboxRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BtcUtxoCollectionFlowTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000037");
    private static final UUID CUSTODY_ADDRESS = UUID.fromString("00000000-0000-0000-0000-000000000038");
    private static final String SOURCE = "bcrt1q-source";
    private static final String COLLECTOR = "bcrt1q-collector";
    private static final AssetRuntimeMetadata BTC = AssetRuntimeMetadata.fromProfile(
            1, "BTC", "BTC", 1, 0, 8, null);

    @Test
    void collectionSignsOnlyCreditedOutputsAndDoesNotCreateWithdrawalOrder() {
        var runtime = mock(BlockchainRuntimeService.class);
        var repository = mock(ChainJdbcRepository.class);
        var switches = mock(WalletRuntimeConfigService.class);
        var feeRates = mock(ChainFeeRateService.class);
        var outbox = mock(WalletOutboxRepository.class);
        when(switches.isTaskEnabled("BTC", WalletRuntimeConfigService.TASK_WITHDRAW)).thenReturn(false);
        when(switches.isTaskEnabled("BTC", WalletRuntimeConfigService.TASK_COLLECTION)).thenReturn(true);
        when(runtime.assetMetadata("BTC")).thenReturn(BTC);
        when(runtime.depositConfirmationThreshold(BTC)).thenReturn(1L);
        when(runtime.dustThresholdAtomic(BTC)).thenReturn(546L);
        when(feeRates.get("BTC")).thenReturn("1");
        when(repository.listCollectableLedgerBalances(eq("BTC"), eq(BigDecimal.ZERO), anyInt()))
                .thenReturn(List.of(CollectionCandidateRecord.builder().tenantId(TENANT)
                        .custodyAddressId(CUSTODY_ADDRESS).chain("BTC").assetSymbol("BTC")
                        .address(SOURCE).userId(37L).amount(new BigDecimal("0.00100000")).build()));
        when(repository.findActiveTenantCollectionAddress(TENANT, "BTC")).thenReturn(Optional.of(COLLECTOR));
        var first = UtxoTransaction.builder().txId("a".repeat(64)).seq((short) 0)
                .address(SOURCE).balance(new BigDecimal("0.00050000")).credited(true).build();
        var second = UtxoTransaction.builder().txId("b".repeat(64)).seq((short) 1)
                .address(SOURCE).balance(new BigDecimal("0.00050000")).credited(true).build();
        when(repository.listCreditedSpendableUtxosAtAddress(eq(TENANT), eq("BTC"), eq("BTC"),
                eq(SOURCE), eq(1L), anyInt())).thenReturn(List.of(first, second));
        var address = ChainAddressRecord.builder().tenantId(TENANT).chain("BTC").assetSymbol("BTC")
                .address(SOURCE).walletRole("DEPOSIT").userId(37L).biz(0)
                .addressIndex(1L).derivationPath("m/44'/0'/0'/37'/1").build();
        when(repository.findChainAddressByAddress(TENANT, "BTC", SOURCE)).thenReturn(Optional.of(address));
        when(repository.createCollectionRecord(eq(TENANT), eq(CUSTODY_ADDRESS), anyString(),
                eq("BTC"), eq("BTC"), eq(SOURCE), eq(COLLECTOR), any(), any(), isNull())).thenReturn(1);
        when(repository.createBitcoinLikeSigningTransaction(eq(BTC), eq("COLLECTION"), anyString(), any()))
                .thenAnswer(invocation -> {
                    WithdrawTransaction transaction = invocation.getArgument(3);
                    transaction.setId(42);
                    return transaction;
                });
        when(repository.lockUtxo(eq(TENANT), eq("BTC"), anyString(), anyInt(), eq("42"))).thenReturn(1);
        when(repository.claimCollectionSigning(eq(TENANT), eq("BTC"), anyString(), isNull())).thenReturn(1);

        new UtxoBatchService(runtime, repository, switches, feeRates, new ObjectMapper(), outbox)
                .execute("BTC");

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> fee = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repository).createCollectionRecord(eq(TENANT), eq(CUSTODY_ADDRESS), anyString(),
                eq("BTC"), eq("BTC"), eq(SOURCE), eq(COLLECTOR), amount.capture(), fee.capture(), isNull());
        assertEquals(0, amount.getValue().add(fee.getValue()).compareTo(new BigDecimal("0.00100000")));
        verify(repository, times(2)).lockUtxo(eq(TENANT), eq("BTC"), anyString(), anyInt(), eq("42"));
        verify(outbox).insert(any(), eq(TENANT), anyString(), eq("CHAIN_SIGNING_TRANSACTION"),
                eq("42"), eq("42"), anyString());
        verify(repository, never()).createTenantWithdrawalOrder(any(), anyString(), anyLong(),
                anyString(), anyString(), any(), any(), anyString(), any(), any());
        verify(repository, never()).freezeLedgerBalance(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void confirmedCollectionMarksUtxosSpentWithoutDebitingUserLedger() {
        var repository = mock(ChainJdbcRepository.class);
        when(repository.markCollectionConfirmed(eq(TENANT), eq("BTC"), eq("COLL-1"), eq("txid"))).thenReturn(1);
        var transaction = WithdrawTransaction.builder().id(42).txId("txid").status((short) 2)
                .signature("{\"operationType\":\"COLLECTION\",\"tenantId\":\"" + TENANT
                        + "\",\"collectionNo\":\"COLL-1\",\"fee\":100,\"utxos\":[{}],\"withdraw\":[{\"balance\":0.001}]}").build();

        when(repository.lockBitcoinLikeSigningTransaction(BTC, 42)).thenReturn(Optional.of(transaction));
        when(repository.updateCollectionAmounts(eq(TENANT), eq("BTC"), eq("COLL-1"), any(), any())).thenReturn(1);
        when(repository.markUtxosSpent("BTC", "42", "txid")).thenReturn(1);
        new BitcoinLikeSettlementService(repository, new ObjectMapper())
                .settleConfirmed(transaction, "txid", BTC);

        verify(repository).markCollectionConfirmed(TENANT, "BTC", "COLL-1", "txid");
        verify(repository).markUtxosSpent("BTC", "42", "txid");
        verify(repository, never()).confirmWithdrawalAndSettle(any(), anyString(), anyString(),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void collectionBroadcastDoesNotEmitWithdrawalEvent() {
        var repository = mock(ChainJdbcRepository.class);
        var runtime = mock(BlockchainRuntimeService.class);
        var queue = mock(PgmqClient.class);
        var leases = mock(WalletTaskLeaseService.class);
        when(leases.ownerId()).thenReturn("test");
        when(runtime.isBitcoinLikeRuntime(any(AssetRuntimeMetadata.class))).thenReturn(true);
        when(runtime.chainName(any(AssetRuntimeMetadata.class))).thenReturn("BTC");
        when(runtime.broadcastSignedTransaction(any(), any())).thenReturn("txid");
        when(repository.claimBitcoinLikeBroadcast(any(), eq(42L), anyString())).thenReturn(true);
        when(repository.updateCollectionStatus(eq(TENANT), eq("BTC"), eq("COLL-1"),
                eq("SENT"), eq("txid"), isNull(), isNull())).thenReturn(1);
        var signed = WithdrawTransaction.builder().id(42).currency(1).status(Constants.SIGNING)
                .signature("{\"operationType\":\"COLLECTION\",\"tenantId\":\"" + TENANT
                        + "\",\"signingRequestId\":\"00000000-0000-0000-0000-000000000042\""
                        + ",\"collectionNo\":\"COLL-1\",\"valid\":true,\"withdraw\":[]}").build();
        BTC.applyTo(signed);
        when(repository.lockBitcoinLikeSigningTransaction(any(), eq(42))).thenReturn(Optional.of(signed));
        var service = new TransactionService(mock(AddressService.class), runtime, repository,
                mock(WalletRuntimeConfigService.class), queue, new ObjectMapper(), leases);

        assertTrue(service.sendWithdrawTransaction(signed));

        verify(repository).updateCollectionStatus(TENANT, "BTC", "COLL-1", "SENT", "txid", null, null);
        verify(repository, never()).updateWithdrawalStatus(any(), anyString(), anyString(),
                anyString(), any(), any(), any());
        verifyNoInteractions(queue);
    }

    @Test
    void internalCollectionOutputIsNotCreditedOrPublishedAsDeposit() {
        var repository = mock(ChainJdbcRepository.class);
        var runtime = mock(BlockchainRuntimeService.class);
        var queue = mock(PgmqClient.class);
        var leases = mock(WalletTaskLeaseService.class);
        when(leases.ownerId()).thenReturn("test");
        when(runtime.assetMetadata(1)).thenReturn(BTC);
        when(runtime.isBitcoinLikeRuntime(BTC)).thenReturn(true);
        when(runtime.chainName(BTC)).thenReturn("BTC");
        when(repository.findChainAddressByAddress("BTC", COLLECTOR)).thenReturn(Optional.of(
                ChainAddressRecord.builder().tenantId(TENANT).chain("BTC")
                        .address(COLLECTOR).userId(198L).build()));
        when(repository.findActiveTenantCollectionAddress(TENANT, "BTC"))
                .thenReturn(Optional.of(COLLECTOR));
        var dto = TransactionDTO.builder().txId("txid-0").currency(1).address(COLLECTOR)
                .balance(new BigDecimal("0.00099")).confirmNum(1L).biz(1).build();
        var service = new TransactionService(mock(AddressService.class), runtime, repository,
                mock(WalletRuntimeConfigService.class), queue, new ObjectMapper(), leases);

        service.saveTransaction(dto);

        verify(repository, never()).recordAndCreditDeposit(any(), anyLong(), anyInt(), anyString());
        verifyNoInteractions(queue);
    }
}
