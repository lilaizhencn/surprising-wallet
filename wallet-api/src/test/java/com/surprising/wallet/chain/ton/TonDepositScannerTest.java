package com.surprising.wallet.chain.ton;

import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.TonReceiptTestSupport;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.ton.ton4j.cell.*;
import org.ton.ton4j.tlb.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TonDepositScannerTest {
    private static final ObjectMapper JSON=new ObjectMapper();
    private final TonCenterClient rpc=mock(TonCenterClient.class);
    private final ChainAddressRepository addresses=mock(ChainAddressRepository.class);
    private final ChainProfileRepository profiles=mock(ChainProfileRepository.class);
    private final TokenConfigRepository tokens=mock(TokenConfigRepository.class);
    private final TonTransactionRepository facts=mock(TonTransactionRepository.class);
    private final DepositService deposits=mock(DepositService.class);
    private final DepositRecordRepository receipts=mock(DepositRecordRepository.class);
    private final AccountChainProfile profile=AccountChainProfile.builder().chain("TON").network("testnet").depositConfirmations(1).scanBatchSize(100).build();
    private final TonAddressService normalization=new TonAddressService(rpc,addresses,profiles,tokens);
    private JsonNode fixture(String name)throws Exception{try(var in=getClass().getResourceAsStream("/mpc/ton-actual-"+name+"-receipt.json")){return JSON.readTree(in);}}
    private ChainAddressRecord address(String value,String symbol,String owner){return ChainAddressRecord.builder().tenantId(UUID.randomUUID()).mpcKeyId(UUID.randomUUID()).chain("TON").assetSymbol(symbol).address(value).ownerAddress(owner).accountId("tenant-account").userId(42L).biz(1).addressIndex(7L).walletRole("DEPOSIT").enabled(true).build();}
    private TonDepositScanner scanner(){return new TonDepositScanner(rpc,normalization,addresses,profiles,tokens,mock(ChainScanHeightRepository.class),facts,deposits,receipts,mock(WalletRuntimeConfigService.class));}
    private void setup(){
        when(profiles.findAccountByChain("TON")).thenReturn(Optional.of(profile));
        when(rpc.masterchainInfo()).thenReturn(JSON.createObjectNode().set("last",JSON.createObjectNode().put("seqno",123)));
        when(rpc.transactions(anyString(),anyInt())).thenAnswer(call->JSON.createArrayNode());
    }
    @Test void nativeCreditUsesRawPrincipalAndIgnoresForgedJsonAmount()throws Exception{
        setup();var f=fixture("native");String recipient=f.path("unsigned").path("recipient").asText();var tracked=address(recipient,"TON",recipient);
        var transaction=(ObjectNode)f.path("recipient").deepCopy();((ObjectNode)transaction.path("in_msg")).put("value","999999999999999999");
        when(addresses.listEnabled("TON","TON")).thenReturn(List.of(tracked));when(addresses.listEnabled("TON")).thenReturn(List.of(tracked));
        when(rpc.transactions(recipient,100)).thenReturn(JSON.createArrayNode().add(transaction));
        var events=scanner().scanAndCredit();assertEquals(1,events.size());assertEquals(0,new BigDecimal("0.1").compareTo(events.getFirst().amount()));
        verify(deposits).recordAndCredit(any(),eq(0L),eq(1),eq("tenant-account"));
    }
    @Test void internalNativeTransferAndCanonicalReplayCannotCreditAgain()throws Exception{
        setup();var f=fixture("native");String recipient=f.path("unsigned").path("recipient").asText(),source=f.path("unsigned").path("sender").asText();var tracked=address(recipient,"TON",recipient);
        when(addresses.listEnabled("TON","TON")).thenReturn(List.of(tracked));when(addresses.listEnabled("TON")).thenReturn(List.of(tracked,address(source,"TON",source)));
        when(rpc.transactions(recipient,100)).thenReturn(JSON.createArrayNode().add(f.path("recipient")));
        assertTrue(scanner().scanAndCredit().isEmpty());verifyNoInteractions(deposits);
        when(addresses.listEnabled("TON")).thenReturn(List.of(tracked));when(receipts.existsCanonical(eq("TON"),anyString(),eq(0L))).thenReturn(true);
        assertTrue(scanner().scanAndCredit().isEmpty());verifyNoInteractions(deposits);
    }
    private JsonNode jettonSetup(boolean internal)throws Exception{
        setup();var f=fixture("usdt");var u=f.path("unsigned");String owner=u.path("recipient").asText(),wallet=u.path("recipientJettonWallet").asText(),master=u.path("jettonMaster").asText();
        var nativeOwner=address(owner,"TON",owner);var jetton=nativeOwner.toBuilder().assetSymbol("USDT").address(wallet).build();
        var token=TokenDefinition.builder().chain("TON").symbol("USDT").contractAddress(master).decimals(6).build();
        when(tokens.listDefinitions("TON","testnet")).thenReturn(List.of(token));
        when(addresses.listEnabled("TON","TON")).thenReturn(List.of(nativeOwner));when(addresses.listEnabled("TON","USDT")).thenReturn(List.of(jetton));
        when(addresses.listEnabled("TON")).thenReturn(internal?List.of(nativeOwner,jetton,address(u.path("sender").asText(),"TON",u.path("sender").asText())):List.of(nativeOwner,jetton));
        when(addresses.find("TON","USDT",42,1,7,"DEPOSIT")).thenReturn(Optional.of(jetton));
        when(rpc.addressInformation(wallet)).thenReturn(JSON.createObjectNode().put("state","active"));when(rpc.jettonWallet(owner,master)).thenReturn(wallet);
        when(rpc.jettonData(wallet)).thenReturn(new TonCenterClient.JettonData(java.math.BigInteger.valueOf(1_000_000),owner,master,"ab".repeat(32)));
        when(rpc.transactions(wallet,100)).thenReturn(JSON.createArrayNode().add(f.path("recipientJetton")));return f;
    }
    @Test void onlySuccessfulConfiguredJettonWalletCreditsExactTokenAmount()throws Exception{
        jettonSetup(false);var events=scanner().scanAndCredit();assertEquals(1,events.size());assertEquals("USDT",events.getFirst().assetSymbol());
        assertEquals(0,BigDecimal.ONE.compareTo(events.getFirst().amount()));verify(deposits).recordAndCredit(any(),eq(0L),eq(1),eq("tenant-account"));
    }
    @Test void internalJettonTransferCannotBeCreditedAsExternalDeposit()throws Exception{
        jettonSetup(true);assertTrue(scanner().scanAndCredit().isEmpty());verifyNoInteractions(deposits);
    }
    private JsonNode changedJetton(JsonNode original,java.util.function.Consumer<Transaction> change){
        var raw=Cell.fromBocBase64(original.path("data").asText());var tx=Transaction.deserialize(CellSlice.beginParse(raw));change.accept(tx);
        var cell=TonReceiptTestSupport.serialize(tx,raw);var result=(ObjectNode)original.deepCopy();result.put("data",Base64.getEncoder().encodeToString(cell.toBoc(false)));
        ((ObjectNode)result.path("transaction_id")).put("hash",HexFormat.of().formatHex(cell.hash()));return result;
    }
    @Test void abortedJettonExecutionOrNotificationAloneNeverCreditsPrincipal()throws Exception{
        var f=jettonSetup(false);String wallet=f.path("unsigned").path("recipientJettonWallet").asText();
        var aborted=changedJetton(f.path("recipientJetton"),tx->((TransactionDescriptionOrdinary)tx.getDescription()).setAborted(true));
        when(rpc.transactions(wallet,100)).thenReturn(JSON.createArrayNode().add(aborted));assertTrue(scanner().scanAndCredit().isEmpty());
        var notification=changedJetton(f.path("recipientJetton"),tx->tx.getInOut().getIn().setBody(CellBuilder.beginCell().storeUint(0x7362d09cL,32).storeUint(1,64)
                .storeCoins(java.math.BigInteger.valueOf(1_000_000)).storeAddress(org.ton.ton4j.address.Address.of(f.path("unsigned").path("sender").asText())).storeBit(false).endCell()));
        when(rpc.transactions(wallet,100)).thenReturn(JSON.createArrayNode().add(notification));assertTrue(scanner().scanAndCredit().isEmpty());verifyNoInteractions(deposits);
    }
    @Test void wrongMasterGetterCannotCreditAnOtherwiseValidTransfer()throws Exception{
        var f=jettonSetup(false);String wallet=f.path("unsigned").path("recipientJettonWallet").asText();
        when(rpc.jettonData(wallet)).thenReturn(new TonCenterClient.JettonData(java.math.BigInteger.TEN,f.path("unsigned").path("recipient").asText(),"0:"+"ab".repeat(32),"cd".repeat(32)));
        assertThrows(IllegalStateException.class,()->scanner().scanAndCredit());verifyNoInteractions(deposits);
    }
}
