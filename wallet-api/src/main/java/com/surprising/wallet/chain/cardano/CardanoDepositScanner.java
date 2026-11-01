package com.surprising.wallet.chain.cardano;

import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.surprising.wallet.chain.model.CardanoOwnedOutput;
import com.surprising.wallet.chain.model.ChainScanPoint;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.common.chain.TokenDefinition;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.CardanoSubmissionService;
import com.surprising.wallet.service.DepositService;
import com.surprising.wallet.service.WalletRuntimeConfigService;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Confirmed original native blocks; each block, owned-output history and custody credit share one transaction. */
@Service
@RequiredArgsConstructor
public class CardanoDepositScanner {
    private static final String CHAIN = "ADA";
    private static final String SCANNER = CardanoSubmissionService.SCANNER;
    private final CardanoRpcClient rpc;
    private final ChainAddressRepository addresses;
    private final ChainProfileRepository profiles;
    private final TokenConfigRepository tokens;
    private final ChainAssetRepository assets;
    private final ChainScanHeightRepository heights;
    private final ChainScanBlockRepository blocks;
    private final CardanoOwnedOutputRepository outputs;
    private final CardanoSubmissionService submissions;
    private final DepositService deposits;
    private final PlatformTransactionManager manager;
    private final ObjectMapper json;
    @NonNull private final WalletRuntimeConfigService runtime;

    /** Previously credited funds remain subject to reorg reconciliation when new scans are disabled. */
    public int reconcileObserved() {
        var state = heights.nativeState(CHAIN, SCANNER, false);
        if (state.isEmpty()) return 0;
        String genesis = genesisHash();
        require(genesis.equals(state.get().genesisHash()), "Cardano scanner native network changed");
        var candidates = blocks.recentNative(CHAIN, SCANNER, 1_000);
        require(!candidates.isEmpty() && candidates.getFirst().point().height() == state.get().safeHeight(),
                "Cardano native cursor has no exact stored header");
        var common = rpc.commonPoint(candidates.stream().map(ChainScanBlockRepository.NativeBlock::point).toList());
        if (common.equals(candidates.getFirst().point())) return 0;
        long best = rpc.tip().getHeight();
        return Objects.requireNonNull(new TransactionTemplate(manager).execute(status -> {
            var locked = heights.nativeState(CHAIN, SCANNER, true).orElseThrow();
            if (locked.safeHeight() != state.get().safeHeight()) return 0;
            if (!blocks.nativePoint(CHAIN, SCANNER, locked.safeHeight()).orElseThrow().point().equals(candidates.getFirst().point())) return 0;
            require(locked.genesisHash().equals(genesis), "Cardano scan genesis changed during rollback");
            require(blocks.nativePoint(CHAIN, SCANNER, common.height()).orElseThrow().point().equals(common),
                    "Cardano common header changed during rollback");
            int reversed = deposits.rollbackAbove(CHAIN, common.height(), common.hash());
            outputs.removeAbove(common.height());
            blocks.removeAbove(CHAIN, SCANNER, common.height());
            require(heights.advanceNative(CHAIN, SCANNER, genesis, Math.max(best, common.height()),
                    locked.safeHeight(), common.height()) == 1, "Cardano rollback cursor changed");
            return reversed;
        }));
    }

    public List<DepositEvent> scanAndCredit() {
        runtime.requireTaskEnabled(CHAIN, WalletRuntimeConfigService.TASK_SCAN, "Cardano scanAndCredit");
        reconcileObserved();
        var state = heights.nativeState(CHAIN, SCANNER, false);
        if (state.isEmpty()) {
            require(addresses.listMpcAddresses(CHAIN).isEmpty(), "Cardano owned address has no publication anchor");
            return List.of();
        }
        var profile = profiles.findAccountByChain(CHAIN).orElseThrow();
        int required = Math.max(1, profile.getDepositConfirmations());
        var head = rpc.tip();
        long safe = head.getHeight() - required + 1;
        var previous = blocks.nativePoint(CHAIN, SCANNER, state.get().safeHeight()).orElseThrow().point();
        if (safe <= previous.height()) return List.of();
        int limit = Math.toIntExact(Math.min(500L, Math.max(1L, profile.getScanMaxBlocksPerRun() != null && profile.getScanMaxBlocksPerRun() > 0
                ? profile.getScanMaxBlocksPerRun() : profile.getScanBatchSize() == null ? 50 : profile.getScanBatchSize())));
        var batch = rpc.blocksAfter(CardanoRpcClient.reference(previous), safe, limit);
        List<DepositEvent> events = new ArrayList<>();
        for (var original : batch) {
            var block = CardanoExecutionReceipt.verifyBlock(original);
            require(block.reference().getHeight() == previous.height() + 1 && Objects.equals(block.parentHash(), previous.hash()),
                    "Cardano deposit block is not the next original parent");
            var expected = previous;
            var credited = new TransactionTemplate(manager).execute(status -> {
                var locked = heights.nativeState(CHAIN, SCANNER, true).orElseThrow();
                if (locked.safeHeight() != expected.height()) return null;
                require(locked.genesisHash().equals(state.get().genesisHash())
                        && blocks.nativePoint(CHAIN, SCANNER, expected.height()).orElseThrow().point().equals(expected),
                        "Cardano deposit cursor changed before credit");
                var result = credit(block, head.getHeight(), required, profile.getNetwork());
                var point = CardanoRpcClient.point(block.reference());
                blocks.insertNative(CHAIN, SCANNER, point, block.parentHash());
                require(heights.advanceNative(CHAIN, SCANNER, locked.genesisHash(), head.getHeight(), expected.height(), point.height()) == 1,
                        "Cardano deposit cursor raced");
                return result;
            });
            if (credited == null) break;
            events.addAll(credited);
            previous = CardanoRpcClient.point(block.reference());
        }
        return List.copyOf(events);
    }

    private List<DepositEvent> credit(CardanoExecutionReceipt.Block block, long best, int required, String network) {
        Map<String, List<ChainAddressRecord>> owned = new HashMap<>();
        for (var address : addresses.listMpcAddresses(CHAIN)) owned.computeIfAbsent(address.getAddress(), ignored -> new ArrayList<>()).add(address);
        Map<String, TokenDefinition> configured = new HashMap<>();
        for (var token : tokens.listDefinitions(CHAIN, network)) if (Boolean.TRUE.equals(token.getActive()))
            configured.put(CardanoAssetUnit.fromTokenContract(token.getContractAddress()), token);
        boolean nativeEnabled = assets.findActiveDefinition(CHAIN, "ADA").isPresent();
        var point = CardanoRpcClient.point(block.reference());
        int confirmations = Math.toIntExact(Math.min(Integer.MAX_VALUE, best - point.height() + 1));
        List<DepositEvent> events = new ArrayList<>();
        for (var tx : block.transactions()) {
            List<TransactionInput> inputs = tx.successful() ? tx.body().getInputs() : tx.body().getCollateral();
            var inputRefs = new HashSet<String>();
            for (var input : inputs) inputRefs.add(input.getTransactionId() + ":" + input.getIndex());
            var known = outputs.findByTransactions(inputs.stream().map(TransactionInput::getTransactionId).distinct().toList()).stream()
                    .filter(output -> inputRefs.contains(output.txHash() + ":" + output.outputIndex())).toList();
            var withdrawals = known.stream().map(CardanoOwnedOutput::tenantId).distinct()
                    .flatMap(tenant -> submissions.withdrawal(tenant, tx.hash()).stream()).toList();
            for (var original : CardanoExecutionReceipt.outputs(tx)) {
                var output = original.value();
                var owners = owned.get(output.getAddress());
                if (owners == null) continue;
                var owner = owners.getFirst();
                require(owners.stream().allMatch(row -> row.getTenantId().equals(owner.getTenantId())),
                        "Cardano output address has conflicting tenant ownership");
                outputs.insert(new CardanoOwnedOutput(tx.hash(), original.index(), owner.getTenantId(), owner.getId(), owner.getAddress(),
                        point.height(), point.hash(), original.originalCbor()));
                if (!known.isEmpty() && withdrawals.stream().noneMatch(payment -> payment.unsigned().recipient().equals(output.getAddress()))) continue;
                var amounts = amounts(output);
                int ordinal = 0;
                for (var amount : amounts.entrySet()) {
                    String unit = amount.getKey();
                    int assetOrdinal = unit.equals("lovelace") ? 0 : ++ordinal;
                    var token = configured.get(unit);
                    if (amount.getValue().signum() <= 0 || (unit.equals("lovelace") ? !nativeEnabled : token == null)) continue;
                    String symbol = unit.equals("lovelace") ? "ADA" : token.getSymbol();
                    int decimals = unit.equals("lovelace") ? 6 : token.getDecimals();
                    var target = owners.stream().filter(row -> Boolean.TRUE.equals(row.getEnabled()) && "DEPOSIT".equals(row.getWalletRole())
                            && (symbol.equals(row.getAssetSymbol()) || "ADA".equals(row.getAssetSymbol())))
                            .min(Comparator.comparingInt(row -> symbol.equals(row.getAssetSymbol()) ? 0 : 1));
                    if (target.isEmpty()) continue;
                    var audit = json.createObjectNode().put("blockHash", point.hash()).put("slot", point.slot())
                            .put("bodyCbor", HexFormat.of().formatHex(tx.bodyBytes())).put("outputIndex", original.index())
                            .put("outputCbor", original.originalCbor()).put("successful", tx.successful());
                    // Native UTxO references do not reveal external source addresses; retain the exact input references instead.
                    var refs = audit.putArray("spentInputs"); inputRefs.stream().sorted().forEach(refs::add);
                    var event = new DepositEvent(ChainType.ADA, symbol, tx.hash(), null, target.get().getAddress(),
                            new BigDecimal(amount.getValue()).movePointLeft(decimals), point.height(), point.hash(), confirmations,
                            unit.equals("lovelace") ? null : unit, json.writeValueAsString(audit));
                    if (deposits.recordAndCredit(event, CardanoAssetUnit.depositLogIndex(original.index(), assetOrdinal), required, target.get().getAccountId())) events.add(event);
                }
            }
        }
        return List.copyOf(events);
    }

    private static Map<String, BigInteger> amounts(com.bloxbean.cardano.client.transaction.spec.TransactionOutput output) {
        // Native always has ordinal zero; asset ordinals include disabled/unconfigured assets and remain stable across config changes.
        Map<String, BigInteger> result = new LinkedHashMap<>(); result.put("lovelace", output.getValue().getCoin());
        var assets = new TreeMap<String, BigInteger>();
        for (var group : output.getValue().getMultiAssets()) for (var asset : group.getAssets())
            assets.merge(CardanoAssetUnit.fromTokenContract(group.getPolicyId() + "." + asset.getNameAsHex()), asset.getValue(), BigInteger::add);
        result.putAll(assets); return result;
    }
    private String genesisHash() {
        var genesis = rpc.genesis();
        require(genesis.hasCardano() && genesis.getGenesis().size() == 32, "Cardano scanner native genesis is absent");
        return HexFormat.of().formatHex(genesis.getGenesis().toByteArray());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
