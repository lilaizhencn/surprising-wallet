package com.surprising.wallet.service;

import com.surprising.wallet.chain.model.ChainCollectionRecord;
import com.surprising.wallet.chain.model.TronTransactionRecord;
import com.surprising.wallet.chain.tron.*;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.common.chain.WithdrawalOrderRecord;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.TronTransactionRepository;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.ArgumentCaptor;
import org.tron.trident.core.key.KeyPair;
import org.tron.trident.proto.Chain;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TronAccountChainServiceTest {
    private static final UUID TENANT = UUID.fromString("77020000-0000-0000-0000-000000000021");
    private static final ECKey KEY = ECKey.fromPrivate(BigInteger.valueOf(7), true);
    private static final String SOURCE = TronTridentKeyFactory.fromBitcoinEcKey(KEY).toBase58CheckAddress();
    private static final String DESTINATION = TronTridentKeyFactory.fromBitcoinEcKey(ECKey.fromPrivate(BigInteger.valueOf(11), true)).toBase58CheckAddress();
    private static final String HASH = "56".repeat(32);
    private static final BigDecimal AMOUNT = new BigDecimal("1.234567");

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void sendsTheSameExactNativeOrTokenPayloadForEitherOperation(boolean collection, boolean nativeAsset) throws Exception {
        var f = new Fixture();
        String symbol = nativeAsset ? "TRX" : "USDT";
        f.send(collection, symbol, AMOUNT);
        verify(f.keys).key(same(f.profile), same(f.from));
        if (nativeAsset) {
            verify(f.nativeTransfers).signTrxTransfer(same(f.client), any(KeyPair.class), eq(DESTINATION), eq(1_234_567L));
            verify(f.nativeTransfers).broadcast(f.client, f.signed);
            verifyNoInteractions(f.tokens);
        } else {
            verify(f.tokens).signTransfer(same(f.client), any(KeyPair.class), eq(f.token.getContractAddress()),
                    eq(DESTINATION), eq(AMOUNT), eq(6), eq(12_500_000L));
            verify(f.tokens).broadcast(f.client, f.signed);
            verifyNoInteractions(f.nativeTransfers);
        }
        var recorded = ArgumentCaptor.forClass(TronTransactionRecord.class);
        verify(f.transactions).upsert(recorded.capture());
        var row = recorded.getValue();
        assertEquals("TRON", row.getChain());
        assertEquals(HASH, row.getTxHash());
        assertEquals(SOURCE, row.getFromAddress());
        assertEquals(DESTINATION, row.getToAddress());
        assertEquals(symbol, row.getAssetSymbol());
        assertEquals(nativeAsset ? null : f.token.getContractAddress(), row.getContractAddress());
        assertEquals(0, AMOUNT.compareTo(row.getAmount()));
        assertEquals(0, row.getFee().signum());
        assertEquals("SENT", row.getStatus());
        var order = inOrder(f.collections, f.factory, f.transactions, f.client);
        if (collection) order.verify(f.collections).claimSigning(TENANT, "TRON", "collection-test", null);
        order.verify(f.factory).create();
        order.verify(f.transactions).upsert(any(TronTransactionRecord.class));
        if (collection) order.verify(f.collections).updateStatus(TENANT, "TRON", "collection-test", "SENT", HASH, null, null);
        order.verify(f.client).close();
        if (!collection) verifyNoInteractions(f.collections);
    }

    @Test
    void aLostCollectionClaimDoesNotOpenOrUseAnyRpcClient() throws Exception {
        var f = new Fixture();
        when(f.collections.claimSigning(TENANT, "TRON", "collection-test", null)).thenReturn(0);
        f.send(true, "TRX", AMOUNT);
        verifyNoInteractions(f.factory, f.client, f.keys, f.nativeTransfers, f.tokens, f.transactions);
        verify(f.collections, never()).updateStatus(any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void aBroadcastFailureClosesTheClientAndKeepsTheCollectionFailurePolicy() throws Exception {
        var f = new Fixture();
        var failure = new IllegalStateException("RPC failure");
        when(f.nativeTransfers.broadcast(f.client, f.signed)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> f.send(true, "TRX", AMOUNT)));
        verifyNoInteractions(f.transactions);
        var order = inOrder(f.nativeTransfers, f.client, f.collections);
        order.verify(f.nativeTransfers).broadcast(f.client, f.signed);
        order.verify(f.client).close();
        order.verify(f.collections).updateStatus(TENANT, "TRON", "collection-test", "FAILED", null, "RPC failure", null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aCloseFailureCannotHideTheKnownCollectionHash(boolean nativeAsset) throws Exception {
        var f = new Fixture();
        doThrow(new IllegalStateException("close failure")).when(f.client).close();
        assertThrows(IllegalStateException.class, () -> f.send(true, nativeAsset ? "TRX" : "USDT", AMOUNT));
        var order = inOrder(f.transactions, f.collections, f.client);
        order.verify(f.transactions).upsert(any(TronTransactionRecord.class));
        order.verify(f.collections).updateStatus(TENANT, "TRON", "collection-test", "SENT", HASH, null, null);
        order.verify(f.client).close();
        order.verify(f.collections).updateStatus(TENANT, "TRON", "collection-test", "FAILED", null, "close failure", null);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFractionOfASunCannotBeSilentlyRounded(boolean collection) throws Exception {
        var f = new Fixture();
        assertThrows(ArithmeticException.class, () -> f.send(collection, "TRX", new BigDecimal("0.0000001")));
        verifyNoInteractions(f.nativeTransfers, f.tokens, f.transactions);
        verify(f.client).close();
        if (collection) verify(f.collections).updateStatus(eq(TENANT), eq("TRON"), eq("collection-test"), eq("FAILED"),
                isNull(), anyString(), isNull());
    }

    @ParameterizedTest
    @MethodSource("feeLimits")
    void tokenTransfersRetainTheConfiguredFeeLimit(Long configured, long expected) throws Exception {
        var f = new Fixture();
        f.profile.setDefaultFee(configured);
        f.send(false, "USDT", AMOUNT);
        verify(f.tokens).signTransfer(same(f.client), any(KeyPair.class), eq(f.token.getContractAddress()),
                eq(DESTINATION), eq(AMOUNT), eq(6), eq(expected));
    }

    private static Stream<Arguments> feeLimits() {
        return Stream.of(Arguments.of(null, 30_000_000L), Arguments.of(1L, 10_000_000L), Arguments.of(15_000_000L, 15_000_000L));
    }

    private static final class Fixture {
        final CollectionRecordRepository collections = mock(CollectionRecordRepository.class);
        final TronTransactionRepository transactions = mock(TronTransactionRepository.class);
        final AccountSecp256k1KeyService keys = mock(AccountSecp256k1KeyService.class);
        final AccountChainAssetService assets = mock(AccountChainAssetService.class);
        final TronClientFactory factory = mock(TronClientFactory.class);
        final TronTridentClient client = mock(TronTridentClient.class);
        final TronTransactionService nativeTransfers = mock(TronTransactionService.class);
        final TronTrc20Service tokens = mock(TronTrc20Service.class);
        final AccountChainProfile profile = AccountChainProfile.builder().chain("TRON").network("nile").family("tron")
                .nativeSymbol("TRX").defaultFee(12_500_000L).enabled(true).build();
        final ChainAddressRecord from = ChainAddressRecord.builder().tenantId(TENANT).chain("TRON").assetSymbol("TRX")
                .address(SOURCE).userId(1L).biz(7).addressIndex(2L).walletRole("DEPOSIT").enabled(true).build();
        final TokenDefinition token = TokenDefinition.builder().chain("TRON").symbol("USDT").standard("TRC20")
                .contractAddress(DESTINATION).decimals(6).active(true).build();
        final TronTransactionService.SignedTronTransaction signed = new TronTransactionService.SignedTronTransaction(HASH, Chain.Transaction.getDefaultInstance());
        final TronAccountChainService service = new TronAccountChainService(collections, transactions, keys, assets, factory,
                nativeTransfers, tokens, mock(WithdrawalSettlementService.class));

        Fixture() throws Exception {
            when(factory.create()).thenReturn(client);
            when(keys.key(profile, from)).thenReturn(KEY);
            when(assets.isNative(same(profile), anyString())).thenAnswer(invocation -> "TRX".equals(invocation.getArgument(1)));
            when(assets.requireToken("TRON", "USDT")).thenReturn(token);
            when(collections.claimSigning(TENANT, "TRON", "collection-test", null)).thenReturn(1);
            when(nativeTransfers.signTrxTransfer(same(client), any(KeyPair.class), anyString(), anyLong())).thenReturn(signed);
            when(tokens.signTransfer(same(client), any(KeyPair.class), anyString(), anyString(), any(), anyInt(), anyLong())).thenReturn(signed);
            when(nativeTransfers.broadcast(client, signed)).thenReturn("untrusted-remote-hash");
            when(tokens.broadcast(client, signed)).thenReturn("untrusted-remote-hash");
        }

        void send(boolean collection, String symbol, BigDecimal amount) throws Exception {
            if (collection) service.processCollection(profile, ChainCollectionRecord.builder().tenantId(TENANT)
                    .chain("TRON").collectionNo("collection-test").assetSymbol(symbol).fromAddress(SOURCE)
                    .toAddress(DESTINATION).amount(amount).build(), from);
            else assertEquals(HASH, service.broadcast(profile, WithdrawalOrderRecord.builder().tenantId(TENANT)
                    .chain("TRON").orderNo("withdrawal-test").assetSymbol(symbol).fromAddress(SOURCE)
                    .toAddress(DESTINATION).amount(amount).build(), from));
        }
    }
}
