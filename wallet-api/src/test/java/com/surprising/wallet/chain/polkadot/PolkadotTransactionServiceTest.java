package com.surprising.wallet.chain.polkadot;

import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec;
import com.surprising.wallet.common.signing.PolkadotSigningPayload;

import com.surprising.wallet.chain.model.ChainAsset;
import com.surprising.wallet.sdk.signing.polkadot.MpcPolkadotTransactionCodec.Role;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.service.PolkadotSubmissionService.*;
import com.surprising.wallet.service.*;
import java.math.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PolkadotTransactionServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    @Test void collectionRetainsExistentialDepositAndConservativeActualFee() {
        var rpc=mock(PolkadotRuntimeClient.class);var assets=mock(ChainAssetRepository.class);
        when(assets.findActiveDefinition("DOT","DOT")).thenReturn(Optional.of(ChainAsset.builder().decimals(12).build()));
        when(rpc.networkInfo(Role.ASSET_HUB,"source",null)).thenReturn(json.readTree("{\"nativeDecimals\":12,\"nativeBalance\":\"1000000000000\",\"frozenBalance\":\"0\",\"existentialDeposit\":\"100000000000\"}"));
        when(rpc.paymentInfo(eq(Role.ASSET_HUB),eq("source"),eq("source"),any(BigInteger.class),isNull())).thenReturn(new BigInteger("1000000000"));
        var service=new PolkadotTransactionService(rpc,mock(PolkadotSubmissionService.class),mock(ChainProfileRepository.class),assets,mock(TokenConfigRepository.class),mock(ChainAddressRepository.class),mock(WithdrawalOrderRepository.class),mock(CollectionRecordRepository.class),mock(CustodyGasService.class),mock(WalletRuntimeConfigService.class),mock(com.surprising.wallet.service.MpcAuthorizationService.class));
        assertEquals(0,new BigDecimal("0.897").compareTo(service.collectableNativeAmount("source",BigDecimal.TEN,new BigDecimal("0.002"))));
        verify(rpc).paymentInfo(Role.ASSET_HUB,"source","source",new BigInteger("900000000000"),null);
        when(rpc.networkInfo(Role.ASSET_HUB,"source",null)).thenReturn(json.readTree("{\"nativeDecimals\":12,\"nativeBalance\":\"1000000000000\",\"frozenBalance\":\"500000000000\",\"existentialDeposit\":\"100000000000\"}"));
        assertEquals(0,new BigDecimal("0.397").compareTo(service.collectableNativeAmount("source",BigDecimal.TEN,new BigDecimal("0.002"))));
    }
    @Test void unreviewedExtensionTypesOrProductionRuntimeUnderLocalProfileAreRejected() {
        var info=json.readTree("{\"extrinsicVersion\":4,\"extensionVersion\":0,\"specName\":\"westmint\",\"extensions\":[\"ChargeAssetTxPayment\",\"CheckMetadataHash\"],\"extraFields\":{\"era\":\"ExtrinsicEra\",\"nonce\":\"Compact<Index>\",\"tip\":\"Compact<Balance>\",\"assetId\":\"TAssetConversion\",\"mode\":\"u8\"},\"additionalSignedFields\":{\"specVersion\":\"u32\",\"transactionVersion\":\"u32\",\"genesisHash\":\"Hash\",\"blockHash\":\"Hash\",\"metadataHash\":\"Option<[u8;32]>\"}}");
        assertDoesNotThrow(()->PolkadotTransactionService.verifyMetadata(info,"westend",Role.ASSET_HUB));
        assertThrows(RuntimeException.class,()->PolkadotTransactionService.verifyMetadata(info,"mainnet",Role.ASSET_HUB));
        var changed=json.readTree(info.toString());((tools.jackson.databind.node.ObjectNode)changed.path("extraFields")).put("assetId","u32");
        assertThrows(RuntimeException.class,()->PolkadotTransactionService.verifyMetadata(changed,"westend",Role.ASSET_HUB));
        ((tools.jackson.databind.node.ObjectNode)info).put("specName","statemint");
        assertThrows(RuntimeException.class,()->PolkadotTransactionService.verifyMetadata(info,"localnet",Role.ASSET_HUB));
    }
    @Test void expiredUnknownOriginalIntentIsHeldWithoutResigningOrBroadcast() throws Exception {
        var f=new Recovery();f.proof.put("finalizedHeight",f.u.validUntilHeight());
        assertThrows(IllegalStateException.class,()->f.service.recover(f.op));
        verify(f.rpc,never()).broadcast(any(),anyString());verify(f.submissions,never()).reserve(any(),any(),any());
    }
    @Test void genesisOrRuntimeChangesHoldTheOriginalWitness() throws Exception {
        var f=new Recovery();f.proof.put("genesisHash","0x"+"ff".repeat(32));
        assertThrows(IllegalStateException.class,()->f.service.recover(f.op));verify(f.rpc,never()).broadcast(any(),anyString());
        f.proof.put("genesisHash",f.u.genesisHash());
        when(f.rpc.networkInfo(f.u.role(),f.u.sender(),f.u.assetId())).thenReturn(json.createObjectNode().put("genesisHash",f.u.genesisHash()).put("specVersion",f.u.specVersion()+1).put("transactionVersion",f.u.transactionVersion()));
        assertThrows(IllegalStateException.class,()->f.service.recover(f.op));verify(f.rpc,never()).broadcast(any(),anyString());verify(f.submissions,never()).reserve(any(),any(),any());
    }
    @Test void actualKnownFinalWireRecoversWithoutAnotherBroadcast() throws Exception {
        var f=new Recovery();f.proof.put("found",true);
        assertTrue(f.service.recover(f.op));verify(f.rpc,never()).broadcast(any(),anyString());verify(f.submissions).restoreSent(f.op,f.signed.txHash());
    }
    private final class Recovery {
        final Operation op=new Operation(UUID.randomUUID(),"runtime-recovery",Kind.WITHDRAWAL);
        final PolkadotRuntimeClient rpc=mock(PolkadotRuntimeClient.class);final PolkadotSubmissionService submissions=mock(PolkadotSubmissionService.class);
        final MpcPolkadotTransactionCodec.Unsigned u;final MpcPolkadotTransactionCodec.Signed signed;
        final tools.jackson.databind.node.ObjectNode proof;final PolkadotTransactionService service;
        Recovery()throws Exception {
            JsonNode item=null;try(var input=getClass().getResourceAsStream("/mpc/polkadot-actual-payment-proofs.json")){for(var candidate:json.readTree(input).path("payments"))if(candidate.path("unsigned").path("role").asText().equals("ASSET_HUB") && candidate.path("unsigned").path("assetId").isNull()){item=candidate;break;}}
            assertNotNull(item);u=json.treeToValue(item.path("unsigned"),MpcPolkadotTransactionCodec.Unsigned.class);signed=json.treeToValue(item.path("selected"),MpcPolkadotTransactionCodec.Signed.class);proof=(tools.jackson.databind.node.ObjectNode)json.readTree(item.path("proof").toString());
            var request=mock(MpcSignRequest.class);when(request.id()).thenReturn(UUID.randomUUID());when(submissions.intent(any())).thenAnswer(call->op.equals(call.getArgument(0))?Optional.of(request):Optional.empty());
            when(submissions.commit(op,request.id())).thenReturn(signed);when(submissions.payload(request)).thenReturn(new PolkadotSigningPayload("DOT",new BigDecimal(u.amountAtomic()).movePointLeft(12),BigDecimal.ZERO,12,12,BigInteger.TEN.pow(10),BigInteger.ONE,u));
            when(rpc.proof(u,signed.txHash())).thenReturn(proof);
            service=new PolkadotTransactionService(rpc,submissions,mock(ChainProfileRepository.class),mock(ChainAssetRepository.class),mock(TokenConfigRepository.class),mock(ChainAddressRepository.class),mock(WithdrawalOrderRepository.class),mock(CollectionRecordRepository.class),mock(CustodyGasService.class),mock(WalletRuntimeConfigService.class),mock(com.surprising.wallet.service.MpcAuthorizationService.class));
        }
    }

}
