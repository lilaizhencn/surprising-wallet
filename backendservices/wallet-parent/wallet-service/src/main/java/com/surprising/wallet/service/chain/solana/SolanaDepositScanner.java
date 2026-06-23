package com.surprising.wallet.service.chain.solana;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.SolanaTransactionRecord;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.service.dao.ChainJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class SolanaDepositScanner {
    private static final String CHAIN = "SOLANA";
    private static final String SCANNER = "solana-signature-scanner";

    private final SolanaRpcClient rpc;
    private final ChainJdbcRepository repository;

    @Value("${atomex.solana.network:devnet}")
    private String network = "devnet";

    @Value("${atomex.solana.scan-limit:100}")
    private int scanLimit = 100;

    public List<DepositEvent> scanAndCredit() {
        AccountChainProfile profile = repository.findAccountChainProfile(CHAIN, network)
                .orElseThrow(() -> new IllegalStateException("missing enabled SOLANA/" + network + " profile"));
        long currentSlot = rpc.getSlot();
        List<DepositEvent> events = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, "SOL")) {
            if ("DEPOSIT".equals(address.getWalletRole())) {
                scanAddress(address, null, profile, currentSlot, processed, events);
            }
        }
        for (TokenDefinition token : repository.listTokens(CHAIN)) {
            for (ChainAddressRecord address : repository.listChainAddresses(CHAIN, token.getSymbol())) {
                if ("DEPOSIT".equals(address.getWalletRole())) {
                    scanAddress(address, token, profile, currentSlot, processed, events);
                }
            }
        }
        long safeSlot = Math.max(0, currentSlot - profile.getDepositConfirmations() + 1L);
        repository.updateScanHeight(CHAIN, SCANNER, currentSlot, safeSlot);
        return events;
    }

    private void scanAddress(ChainAddressRecord tracked, TokenDefinition token, AccountChainProfile profile,
                             long currentSlot, Set<String> processed, List<DepositEvent> events) {
        ArrayNode signatures = rpc.getSignaturesForAddress(tracked.getAddress(), scanLimit);
        for (JsonNode signatureInfo : signatures) {
            if (!signatureInfo.path("err").isNull() && !signatureInfo.path("err").isMissingNode()) {
                continue;
            }
            String signature = signatureInfo.path("signature").asText();
            if (!processed.add(signature + ":" + tracked.getAddress())) {
                continue;
            }
            JsonNode transaction = rpc.getTransaction(signature);
            if (transaction == null || transaction.isNull()) {
                continue;
            }
            long slot = transaction.path("slot").asLong();
            int confirmations = (int) Math.min(Integer.MAX_VALUE, Math.max(1, currentSlot - slot + 1));
            List<JsonNode> instructions = flattenInstructions(transaction);
            for (int instructionIndex = 0; instructionIndex < instructions.size(); instructionIndex++) {
                JsonNode instruction = instructions.get(instructionIndex);
                JsonNode parsed = instruction.path("parsed");
                String type = parsed.path("type").asText();
                JsonNode info = parsed.path("info");
                DepositEvent event = token == null
                        ? nativeDeposit(signature, tracked, slot, confirmations, transaction, type, info)
                        : tokenDeposit(signature, tracked, token, slot, confirmations, transaction, type, info);
                if (event == null) {
                    continue;
                }
                repository.recordSolanaTransaction(SolanaTransactionRecord.builder()
                        .chain(CHAIN)
                        .signature(signature)
                        .fromAddress(event.fromAddress())
                        .toAddress(event.toAddress())
                        .assetSymbol(event.assetSymbol())
                        .mintAddress(event.tokenAddress())
                        .amount(event.amount())
                        .feeLamports(transaction.path("meta").path("fee").asLong())
                        .slot(slot)
                        .confirmations(confirmations)
                        .status(confirmations >= profile.getDepositConfirmations() ? "CONFIRMED" : "CONFIRMING")
                        .rawPayload(transaction.toString())
                        .build());
                repository.recordAndCreditDeposit(event, instructionIndex,
                        profile.getDepositConfirmations(), tracked.getAccountId());
                events.add(event);
            }
        }
    }

    private DepositEvent nativeDeposit(String signature, ChainAddressRecord tracked, long slot, int confirmations,
                                       JsonNode transaction, String type, JsonNode info) {
        if (!"transfer".equals(type) || !tracked.getAddress().equals(info.path("destination").asText())
                || !info.has("lamports")) {
            return null;
        }
        return new DepositEvent(ChainType.SOLANA, "SOL", signature, info.path("source").asText(),
                tracked.getAddress(), new BigDecimal(info.path("lamports").asText()), slot, confirmations,
                null, transaction.toString());
    }

    private DepositEvent tokenDeposit(String signature, ChainAddressRecord tracked, TokenDefinition token,
                                      long slot, int confirmations, JsonNode transaction,
                                      String type, JsonNode info) {
        if ((!"transfer".equals(type) && !"transferChecked".equals(type))
                || !tracked.getAddress().equals(info.path("destination").asText())) {
            return null;
        }
        String amount = info.has("amount")
                ? info.path("amount").asText()
                : info.path("tokenAmount").path("amount").asText();
        if (amount.isBlank()) {
            return null;
        }
        return new DepositEvent(ChainType.SOLANA, token.getSymbol(), signature,
                info.path("source").asText(), tracked.getAddress(), new BigDecimal(amount), slot, confirmations,
                token.getContractAddress(), transaction.toString());
    }

    private List<JsonNode> flattenInstructions(JsonNode transaction) {
        List<JsonNode> instructions = new ArrayList<>();
        JsonNode outer = transaction.path("transaction").path("message").path("instructions");
        outer.forEach(instructions::add);
        JsonNode innerGroups = transaction.path("meta").path("innerInstructions");
        for (JsonNode group : innerGroups) {
            group.path("instructions").forEach(instructions::add);
        }
        return instructions;
    }
}
