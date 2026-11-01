package com.surprising.wallet.service;

import com.surprising.wallet.sdk.signing.tron.MpcTronTransactionCodec;
import com.surprising.wallet.common.signing.TronSigningPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.surprising.wallet.chain.tron.*;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.TronTransactionRepository;
import com.surprising.wallet.chain.model.ChainCollectionRecord;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.bitcoinj.base.Sha256Hash;
import org.bitcoinj.crypto.ECKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.tron.TronWalletApi;
import org.tron.trident.abi.FunctionEncoder;
import org.tron.trident.abi.datatypes.Function;
import org.tron.trident.core.ApiWrapper;
import org.tron.trident.proto.Chain;
import org.tron.trident.proto.Contract;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Orchestration boundaries; DB/native acceptance uses dedicated probes, not this explicit signature substitute. */
class TronAccountChainServiceTest {
    private static final UUID TENANT=UUID.fromString("77020000-0000-0000-0000-000000000021");
    private static final BigDecimal AMOUNT=new BigDecimal("1.234567");

    @ParameterizedTest @CsvSource({"true,true","true,false","false,true","false,false"})
    void fixedNativeOrTokenIntentIsCommittedBeforeAnyBroadcast(boolean collection,boolean nativeAsset) throws Exception {
        var f=new Fixture();f.send(collection,nativeAsset ? "TRX":"USDT",AMOUNT);
        assertEquals(AMOUNT,f.payload.amount());
        assertEquals(BigInteger.valueOf(1_234_567),f.payload.unsigned().amountAtomic());
        assertEquals(nativeAsset ? null:f.recipient,f.payload.unsigned().contract());
        assertEquals(nativeAsset ? 0:12_500_000,f.payload.unsigned().feeLimitSun());
        assertArrayEquals(TronSubmissionService.memo(f.operation(collection)),MpcTronTransactionCodec.restore(f.payload.unsigned()).getRawData().getData().toByteArray());
        var order=inOrder(f.submissions,f.client);
        order.verify(f.submissions).reserve(eq(f.operation(collection)),same(f.source),any());
        order.verify(f.submissions).sign(eq(f.operation(collection)),eq(f.request.id()));
        order.verify(f.submissions).commit(eq(f.operation(collection)),eq(f.request.id()));
        order.verify(f.client).getTransactionById(eq(f.signed.txId()),any());
        order.verify(f.client).broadcast(any());
        order.verify(f.submissions).observed(f.operation(collection),f.signed.txId());
        if(collection) order.verify(f.submissions).restoreSent(f.operation(true),f.signed.txId());
        order.verify(f.client).close();
    }

    @Test void lostCollectionClaimDoesNotContactSignersOrOpenRpc() throws Exception {
        var f=new Fixture();when(f.collections.claimSigningDurable(any(),anyString(),anyString())).thenReturn(0);
        f.send(true,"TRX",AMOUNT);verifyNoInteractions(f.factory,f.client,f.submissions);
    }

    @Test void unknownBroadcastKeepsTheCommittedIntentAndDoesNotReportCollectionFailure() throws Exception {
        var f=new Fixture();doThrow(new IllegalStateException("lost reply")).when(f.client).broadcast(any());
        assertThrows(IllegalStateException.class,()->f.send(true,"TRX",AMOUNT));
        verify(f.submissions).commit(any(),any());verify(f.submissions).unknown(f.operation(true),"IllegalStateException");
        verify(f.submissions,never()).restoreSent(any(),anyString());
        verify(f.collections,never()).updateStatus(any(),anyString(),anyString(),anyString(),any(),any(),any());
        verify(f.client).close();
    }

    @Test void responseHashMismatchCannotBecomeAnObservedSubmission() throws Exception {
        var f=new Fixture();doReturn("untrusted-hash").when(f.client).broadcast(any());
        assertThrows(IllegalStateException.class,()->f.send(false,"TRX",AMOUNT));
        verify(f.submissions,never()).observed(any(),any());
    }

    @Test void knownTransactionRecoveryUsesOriginalBytesWithoutRebuildingOrRebroadcasting() throws Exception {
        var f=new Fixture();f.send(false,"TRX",AMOUNT);
        var original=f.signed;
        when(f.submissions.intent(f.operation(false))).thenReturn(Optional.of(f.request));
        when(f.client.getTransactionById(eq(original.txId()),any())).thenReturn(Chain.Transaction.parseFrom(HexFormat.of().parseHex(original.transactionHex())));
        assertTrue(f.service.recover(f.operation(false)));
        verify(f.client,times(1)).createTrxTransfer(anyString(),anyString(),anyLong());
        verify(f.client,times(1)).broadcast(any());
        verify(f.submissions).restoreSent(f.operation(false),original.txId());
        assertEquals(original,f.signed);
    }

    @Test void expiredAcceptedIntentCannotSilentlyCreateAnotherTransfer() throws Exception {
        var f=new Fixture();f.timestamp=1_700_000_000_000L;
        assertThrows(IllegalStateException.class,()->f.send(false,"TRX",AMOUNT));
        verify(f.submissions).commit(any(),any());verify(f.client,never()).broadcast(any());
    }

    @Test void fractionalSunIsRejectedBeforeMpcReservation() throws Exception {
        var f=new Fixture();assertThrows(ArithmeticException.class,()->f.send(false,"TRX",new BigDecimal("0.0000001")));
        verify(f.submissions,never()).reserve(any(),any(),any());verify(f.client,never()).broadcast(any());
    }

    private static final class Fixture {
        final ECKey key=new ECKey();
        final String from=TronWalletApi.getAddress(key.getPubKey()),recipient=TronWalletApi.getAddress(new ECKey().getPubKey());
        final AccountChainProfile profile=AccountChainProfile.builder().chain("TRON").network("nile").nativeSymbol("TRX").defaultFee(12_500_000L).build();
        final ChainAddressRecord source=ChainAddressRecord.builder().tenantId(TENANT).chain("TRON").address(from).mpcKeyId(UUID.randomUUID()).build();
        final CollectionRecordRepository collections=mock(CollectionRecordRepository.class);
        final AccountChainAssetService assets=mock(AccountChainAssetService.class);
        final TronClientFactory factory=mock(TronClientFactory.class);
        final TronTridentClient client=mock(TronTridentClient.class);
        final TronSubmissionService submissions=mock(TronSubmissionService.class);
        final TronAccountChainService service=new TronAccountChainService(collections,submissions,assets,factory,
                new TronTransactionService(),new TronTrc20Service());
        TronSigningPayload payload;MpcSignRequest request;MpcTronTransactionCodec.Signed signed;
        long timestamp=System.currentTimeMillis();
        Fixture() throws Exception {
            when(factory.create()).thenReturn(client);
            when(collections.claimSigningDurable(any(),anyString(),anyString())).thenReturn(1);
            when(assets.isNative(same(profile),anyString())).thenAnswer(call->"TRX".equals(call.getArgument(1)));
            when(assets.requireToken(profile,"USDT")).thenReturn(TokenDefinition.builder().symbol("USDT").contractAddress(recipient).decimals(6).build());
            when(client.createTrxTransfer(anyString(),anyString(),anyLong())).thenAnswer(call->tx(Chain.Transaction.Contract.ContractType.TransferContract,
                    Any.pack(Contract.TransferContract.newBuilder().setOwnerAddress(ApiWrapper.parseAddress(from)).setToAddress(ApiWrapper.parseAddress(recipient))
                            .setAmount(call.getArgument(2)).build()),0));
            when(client.createContractCall(anyString(),anyString(),any(),anyLong())).thenAnswer(call->tx(Chain.Transaction.Contract.ContractType.TriggerSmartContract,
                    Any.pack(Contract.TriggerSmartContract.newBuilder().setOwnerAddress(ApiWrapper.parseAddress(from)).setContractAddress(ApiWrapper.parseAddress(recipient))
                            .setData(ByteString.copyFrom(HexFormat.of().parseHex(FunctionEncoder.encode(call.<Function>getArgument(2))))).build()),call.getArgument(3)));
            when(submissions.reserve(any(),same(source),any())).thenAnswer(call->{
                payload=call.getArgument(2);
                var operation=call.<TronSubmissionService.Operation>getArgument(0);
                var command=new MpcSignCommand(TENANT,source.getMpcKeyId(),1,ChainType.TRON,MpcSignCommand.BusinessType.valueOf(operation.kind().name()),
                        operation.reference(),"transaction",MpcSignCommand.Purpose.TRANSACTION,"TRON_TX_JSON",HexFormat.of().formatHex(new ObjectMapper().writeValueAsBytes(payload)),
                        HexFormat.of().formatHex(MpcTronTransactionCodec.digest(payload.unsigned())));
                request=new MpcSignRequest(UUID.randomUUID(),command,MpcSignRequest.State.COMPLETED,UUID.randomUUID());return request;
            });
            when(submissions.payload(any())).thenAnswer(call->payload);
            when(submissions.commit(any(),any())).thenAnswer(call->{
                signed=MpcTronTransactionCodec.assemble(payload.unsigned(),key.getPubKey(),key.sign(Sha256Hash.wrap(MpcTronTransactionCodec.digest(payload.unsigned()))).encodeToDER());return signed;
            });
            when(client.broadcast(any())).thenAnswer(call->signed.txId());
        }
        Chain.Transaction tx(Chain.Transaction.Contract.ContractType type,Any parameter,long fee) {
            return Chain.Transaction.newBuilder().setRawData(Chain.Transaction.raw.newBuilder().setTimestamp(timestamp).setExpiration(timestamp+60_000)
                    .setRefBlockBytes(ByteString.copyFrom(new byte[2])).setRefBlockHash(ByteString.copyFrom(new byte[8])).setFeeLimit(fee)
                    .addContract(Chain.Transaction.Contract.newBuilder().setType(type).setParameter(parameter))).build();
        }
        TronSubmissionService.Operation operation(boolean collection) {return new TronSubmissionService.Operation(TENANT,collection ? "collection":"withdrawal",
                collection ? TronSubmissionService.Kind.COLLECTION:TronSubmissionService.Kind.WITHDRAWAL);}
        void send(boolean collection,String symbol,BigDecimal amount) throws Exception {
            if(collection) service.processCollection(profile,ChainCollectionRecord.builder().tenantId(TENANT).chain("TRON").collectionNo("collection")
                    .assetSymbol(symbol).fromAddress(from).toAddress(recipient).amount(amount).build(),source);
            else service.broadcast(profile,WithdrawalOrderRecord.builder().tenantId(TENANT).chain("TRON").orderNo("withdrawal")
                    .assetSymbol(symbol).fromAddress(from).toAddress(recipient).amount(amount).fee(new BigDecimal("0.1")).build(),source);
        }
    }
}
