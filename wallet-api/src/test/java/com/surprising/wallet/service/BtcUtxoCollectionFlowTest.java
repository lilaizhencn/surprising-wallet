package com.surprising.wallet.service;

import com.surprising.wallet.repository.ChainFeeRateRepository;
import com.surprising.wallet.chain.BitcoinLikeSettlementService;
import com.surprising.wallet.chain.BlockchainRuntimeService;
import com.surprising.wallet.common.chain.AssetRuntimeMetadata;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.CollectionCandidateRecord;
import com.surprising.wallet.common.pojo.UtxoTransaction;
import com.surprising.wallet.chain.model.UtxoSigningTransaction;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.signing.UtxoSigningResult;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.common.chain.AccountChainProfile;
import static com.surprising.wallet.testutil.UtxoSigningTestSupport.*;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.repository.ChainJdbcRepository;
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
        var feeRates = mock(ChainFeeRateRepository.class);
        var submissions = mock(UtxoSigningSubmissionService.class);
        var signing = mock(ChainSigningTransactionRepository.class);
        var profiles = mock(ChainProfileRepository.class);
        when(profiles.findAccountByChain("BTC")).thenReturn(Optional.of(AccountChainProfile.builder().network("regtest").build()));
        when(switches.isTaskEnabled("BTC", WalletRuntimeConfigService.TASK_WITHDRAW)).thenReturn(false);
        when(switches.isTaskEnabled("BTC", WalletRuntimeConfigService.TASK_COLLECTION)).thenReturn(true);
        when(runtime.assetMetadata("BTC")).thenReturn(BTC);
        when(runtime.depositConfirmationThreshold(BTC)).thenReturn(1L);
        when(runtime.dustThresholdAtomic(BTC)).thenReturn(546L);
        when(feeRates.find("BTC")).thenReturn("1");
        when(repository.listCollectableLedgerBalances(eq("BTC"), eq(BigDecimal.ZERO), anyInt()))
                .thenReturn(List.of(CollectionCandidateRecord.builder().tenantId(TENANT)
                        .custodyAddressId(CUSTODY_ADDRESS).chain("BTC").assetSymbol("BTC")
                        .address(SOURCE).userId(37L).amount(new BigDecimal("0.00100000")).build()));
        when(repository.findActiveTenantCollectionAddress(TENANT, "BTC")).thenReturn(Optional.of(COLLECTOR));
        var first = UtxoTransaction.builder().txId("a".repeat(64)).seq(0)
                .address(SOURCE).balance(new BigDecimal("0.00050000")).credited(true).build();
        var second = UtxoTransaction.builder().txId("b".repeat(64)).seq(1)
                .address(SOURCE).balance(new BigDecimal("0.00050000")).credited(true).build();
        when(repository.listCreditedSpendableUtxosAtAddress(eq(TENANT), eq("BTC"), eq("BTC"),
                eq(SOURCE), eq(1L), anyInt())).thenReturn(List.of(first, second));
        var address = ChainAddressRecord.builder().tenantId(TENANT).chain("BTC").assetSymbol("BTC")
                .address(SOURCE).walletRole("DEPOSIT").userId(37L).biz(0)
                .addressIndex(1L).derivationPath("m/44'/0'/0'/37'/1").build();
        when(repository.findChainAddressByAddress(TENANT, "BTC", SOURCE)).thenReturn(Optional.of(address));
        when(repository.createCollectionRecord(eq(TENANT), eq(CUSTODY_ADDRESS), anyString(),
                eq("BTC"), eq("BTC"), eq(SOURCE), eq(COLLECTOR), any(), any(), isNull())).thenReturn(1);
        when(signing.create(any(), anyString())).thenAnswer(invocation ->
                row(42, invocation.getArgument(0), Constants.SIGNING, null));
        when(repository.lockUtxo(eq(TENANT), eq("BTC"), anyString(), anyInt(), eq("42"))).thenReturn(1);
        when(repository.claimCollectionSigning(eq(TENANT), eq("BTC"), anyString(), isNull())).thenReturn(1);

        new UtxoBatchService(runtime, repository, switches, feeRates, submissions, signing, profiles)
                .execute("BTC");

        ArgumentCaptor<BigDecimal> amount = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> fee = ArgumentCaptor.forClass(BigDecimal.class);
        verify(repository).createCollectionRecord(eq(TENANT), eq(CUSTODY_ADDRESS), anyString(),
                eq("BTC"), eq("BTC"), eq(SOURCE), eq(COLLECTOR), amount.capture(), fee.capture(), isNull());
        assertEquals(0, amount.getValue().add(fee.getValue()).compareTo(new BigDecimal("0.00100000")));
        verify(repository, times(2)).lockUtxo(eq(TENANT), eq("BTC"), anyString(), anyInt(), eq("42"));
        verify(submissions).enqueue(eq(TENANT), eq("BTC"), eq(42), any(UUID.class));
        verify(repository, never()).createTenantWithdrawalOrder(any(), anyString(), anyLong(),
                anyString(), anyString(), any(), any(), anyString(), any(), any());
        verify(repository, never()).freezeLedgerBalance(any(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void confirmedCollectionMarksUtxosSpentWithoutDebitingUserLedger() {
        var signing = mock(ChainSigningTransactionRepository.class);
        var collections = mock(CollectionRecordRepository.class);
        var utxos = mock(UtxoRepository.class);
        var settlements = mock(WithdrawalSettlementService.class);
        var request = request(TENANT, UtxoSigningRequest.Operation.COLLECTION, "0.001001", "0.001");
        String hash = "a".repeat(64);
        var transaction = row(42, request, Constants.SENT, signed(hash, 100));
        when(signing.lockById("BTC", 42)).thenReturn(Optional.of(transaction));
        when(signing.update(any())).thenReturn(1);
        when(collections.updateAmounts(eq(TENANT), eq("BTC"), eq("COLL-RBF"), any(), any())).thenReturn(1);
        when(collections.markConfirmed(TENANT, "BTC", "COLL-RBF", hash)).thenReturn(1);
        when(utxos.markSpent("BTC", "42", hash)).thenReturn(1);
        new BitcoinLikeSettlementService(signing, mock(WithdrawalOrderRepository.class), collections, utxos, settlements)
                .settleConfirmed("BTC", 42, hash);
        verify(collections).markConfirmed(TENANT, "BTC", "COLL-RBF", hash);
        verify(utxos).markSpent("BTC", "42", hash);
        verifyNoInteractions(settlements);
    }

    @Test
    void collectionBroadcastDoesNotEmitWithdrawalEvent() {
        var signing = mock(ChainSigningTransactionRepository.class);
        var collections = mock(CollectionRecordRepository.class);
        var runtime = mock(BlockchainRuntimeService.class);
        var queue = mock(PgmqClient.class);
        var orders = mock(WithdrawalOrderRepository.class);
        var request = request(TENANT, UtxoSigningRequest.Operation.COLLECTION, "0.001001", "0.001");
        String hash = "a".repeat(64);
        var result = new UtxoSigningResult(42, "BTC", TENANT, request.requestId(), signed(hash, 100), null);
        when(signing.lockById("BTC", 42)).thenReturn(Optional.of(row(42, request, Constants.SIGNING, null)));
        when(signing.claimBroadcast(eq("BTC"), eq(42), anyString())).thenReturn(true);
        when(signing.update(any())).thenReturn(1);
        when(runtime.broadcastSignedTransaction(any(), any())).thenReturn(hash);
        when(collections.updateStatus(TENANT, "BTC", "COLL-RBF", "SENT", hash, null, null)).thenReturn(1);
        new UtxoBroadcastService(signing, orders, collections, mock(UtxoRepository.class), mock(LedgerBalanceRepository.class),
                runtime, queue, new ObjectMapper(), mock(WalletTaskLeaseService.class)).apply(result);
        verify(collections).updateStatus(TENANT, "BTC", "COLL-RBF", "SENT", hash, null, null);
        verifyNoInteractions(queue, orders);
    }

}
