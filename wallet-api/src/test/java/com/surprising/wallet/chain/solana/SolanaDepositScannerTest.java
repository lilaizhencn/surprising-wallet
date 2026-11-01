package com.surprising.wallet.chain.solana;

import com.surprising.wallet.sdk.signing.solana.MpcSolanaTransactionCodec;

import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.p2p.solanaj.core.Account;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SolanaDepositScannerTest {
    @Test void internalSplAtaSourceCannotCreditAnOwnedDepositAddressAgain(){scan(true);}
    @Test void externalSplAtaSourceStillCreditsTheOwnedAccount(){scan(false);}
    private void scan(boolean internal) {
        var json=new ObjectMapper();var rpc=mock(SolanaRpcClient.class);var addresses=mock(ChainAddressRepository.class);
        var profiles=mock(ChainProfileRepository.class);var tokens=mock(TokenConfigRepository.class);var facts=mock(SolanaTransactionRepository.class);var deposits=mock(DepositService.class);
        var source=ChainAddressRecord.builder().chain("SOLANA").assetSymbol("SOL").address(new Account().getPublicKeyBase58()).walletRole("DEPOSIT").enabled(true).accountId("source").build();
        var recipient=ChainAddressRecord.builder().chain("SOLANA").assetSymbol("SOL").address(new Account().getPublicKeyBase58()).walletRole("DEPOSIT").enabled(true).accountId("recipient").build();
        var token=TokenDefinition.builder().chain("SOLANA").symbol("USDC").contractAddress(new Account().getPublicKeyBase58()).decimals(6).build();
        var profile=new AccountChainProfile();profile.setChain("SOLANA");profile.setNetwork("devnet");profile.setDepositConfirmations(1);profile.setScanBatchSize(100);
        when(profiles.findAccountByChain("SOLANA")).thenReturn(Optional.of(profile));when(tokens.listDefinitions("SOLANA","devnet")).thenReturn(List.of(token));
        when(addresses.listEnabled("SOLANA")).thenReturn(List.of(source,recipient));when(addresses.listEnabled("SOLANA","SOL")).thenReturn(List.of(source,recipient));when(addresses.listEnabled("SOLANA","USDC")).thenReturn(List.of());
        String target=MpcSolanaTransactionCodec.associatedTokenAddress(recipient.getAddress(),token.getContractAddress());
        String from=MpcSolanaTransactionCodec.associatedTokenAddress(internal?source.getAddress():new Account().getPublicKeyBase58(),token.getContractAddress());
        when(rpc.getSlot()).thenReturn(123L);when(rpc.getSignaturesForAddress(anyString(),anyInt())).thenAnswer(call->json.createArrayNode());
        var signatures=json.createArrayNode();signatures.add(json.createObjectNode().put("signature","fixture-signature").putNull("err"));when(rpc.getSignaturesForAddress(eq(target),anyInt())).thenReturn(signatures);
        var receipt=json.createObjectNode().put("slot",120);receipt.set("meta",json.createObjectNode().put("fee",5000));
        var instructions=json.valueToTree(List.of(Map.of("parsed",Map.of("type","transferChecked","info",Map.of("source",from,"destination",target,"mint",token.getContractAddress(),"tokenAmount",Map.of("amount","1000000"))))));
        var message=json.createObjectNode();message.set("instructions",instructions);var transaction=json.createObjectNode();transaction.set("message",message);receipt.set("transaction",transaction);
        when(rpc.getTransaction("fixture-signature")).thenReturn(receipt);
        var scanner=new SolanaDepositScanner(rpc,addresses,profiles,tokens,mock(ChainScanHeightRepository.class),facts,deposits,mock(DepositRecordRepository.class),mock(WalletRuntimeConfigService.class),new SolanaAddressService());
        var events=scanner.scanAndCredit();assertEquals(internal?0:1,events.size());
        if(internal){verifyNoInteractions(deposits);verifyNoInteractions(facts);}
        else{assertEquals("recipient",recipient.getAccountId());assertEquals(0,new java.math.BigDecimal("1").compareTo(events.getFirst().amount()));verify(deposits).recordAndCredit(any(DepositEvent.class),eq(0L),eq(1),eq("recipient"));}
    }
}
