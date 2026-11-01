package com.surprising.wallet.custody;

import com.surprising.wallet.chain.cardano.CardanoPublicTransactionBuilder;
import com.surprising.wallet.chain.cardano.CardanoExecutionReceipt;
import com.surprising.wallet.chain.cardano.CardanoRpcClient;
import com.surprising.wallet.chain.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainRpcNodeRepository;
import com.surprising.wallet.service.ChainRpcNodeService;
import com.surprising.wallet.service.MpcSigningService;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import utxorpc.v1beta.query.Query;
import utxorpc.v1beta.sync.Sync;
import static org.mockito.Mockito.*;

/** Actual official private ledger + native MPC. Public codec acceptance; custody workflows are separate. */
public final class MpcCardanoNativeProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HexFormat HEX = HexFormat.of();
    private MpcCardanoNativeProbe() { }

    public static void verify(JdbcTemplate jdbc, PlatformTransactionManager manager, MpcSigningService signing,
                              MpcNodes nodes, UUID tenant, JsonNode config) throws Exception {
        Path descriptor = Path.of(config.path("cardanoFixture").asText()).toRealPath();
        var fixture = JSON.readTree(Files.readAllBytes(descriptor));
        var endpoint = URI.create(fixture.path("rpcUrl").asText());
        require("http".equals(endpoint.getScheme()) && "127.0.0.1".equals(endpoint.getHost()), "isolated Cardano node required");
        var profiles = new ChainProfileRepository(jdbc);
        var profile = profiles.findAccountByChain("ADA").orElseThrow();
        jdbc.update("update chain_rpc_node set enabled=false where chain='ADA'");
        jdbc.update("insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,min_request_interval_ms) values ('ADA',?,'mpc-test','mpc-cardano-native','rpc',?,0)", profile.getNetwork(), endpoint.toString());
        var rpc = new CardanoRpcClient(profiles, new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc), "mpc-test", 2));
        var genesis = rpc.genesis();
        require(fixture.path("genesisHash").asText().equals(HEX.formatHex(genesis.getGenesis().toByteArray()))
                && genesis.getCardano().getNetworkMagic() == fixture.path("magic").asInt(), "Cardano native genesis differs");
        // Raw protocol probes use an unpublished joint key, so their test payments cannot pollute custody deposits.
        int namespace = new com.surprising.wallet.repository.CustodyTenantTableRepository(jdbc).findById(tenant).orElseThrow().derivationNamespace();
        var key = signing.awaitKey(tenant, ChainType.ADA, 181500001, namespace, 1, nodes);
        String sourceAddress = MpcCardanoTransactionCodec.address(HEX.parseHex(key.publicKeyHex()), false);
        String recipient = MpcCardanoTransactionCodec.address(HEX.parseHex("cd".repeat(32)), false);
        var recipientInitial = rpc.utxos(recipient);
        var funded = fixture(descriptor, "fund", sourceAddress, "100000000", "20000000");
        Query.SearchUtxosResponse initial = awaitBalance(rpc, sourceAddress, "lovelace", BigInteger.valueOf(100_000_000));
        var transaction = new TransactionTemplate(manager);
        var quorum = spy(nodes); doNothing().when(quorum).close();
        int pairIndex = 0;
        BigInteger totalFee = BigInteger.ZERO;
        for (var pair : List.of(List.of(0, 1), List.of(0, 2), List.of(1, 2))) {
            doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
            for (String symbol : List.of("ADA", "USDT", "USDC")) {
                String unit = symbol.equals("ADA") ? "lovelace" : funded.path("units").path(symbol).asText();
                var before = rpc.utxos(sourceAddress);
                var recipientBefore = rpc.utxos(recipient);
                BigInteger amount = BigInteger.valueOf(symbol.equals("ADA") ? 2_000_001 : 1_000_001);
                var prepared = CardanoPublicTransactionBuilder.prepare(genesis, rpc.parameters(), before, HEX.parseHex(key.publicKeyHex()),
                        sourceAddress, recipient, unit, amount, 720);
                var unsigned = prepared.unsigned();
                var request = transaction.execute(status -> signing.reserveSignature(new MpcSignCommand(tenant, key.id(), key.activeEpoch(),
                        ChainType.ADA, MpcSignCommand.BusinessType.DEV_FAUCET, "cardano-codec-" + UUID.randomUUID(), "transaction",
                        MpcSignCommand.Purpose.TRANSACTION, "CARDANO_PAYMENT_JSON", HEX.formatHex(JSON.writeValueAsBytes(unsigned)),
                        HEX.formatHex(MpcCardanoTransactionCodec.signingBytes(unsigned))), quorum.online()));
                signing.awaitSignature(tenant, request.id(), quorum);
                var selected = MpcCardanoTransactionCodec.assemble(unsigned, signing.signature(tenant, request.id()).orElseThrow());
                MpcCardanoTransactionCodec.verifySelected(unsigned, selected);
                require(selected.txHash().equals(fixture(descriptor, "hash", selected.transactionHex()).path("txHash").asText()),
                        "Cardano official CLI transaction hash differs from the exact MPC body");
                require(selected.txHash().equals(rpc.submit(HEX.parseHex(selected.transactionHex()))), "Cardano node accepted another transaction hash");
                var block = inclusion(rpc, prepared.observed(), selected.txHash());
                var outcome = CardanoExecutionReceipt.verify(block, unsigned, selected);
                var receipt = block.getCardano().getBody().getTxList().stream()
                        .filter(tx -> selected.txHash().equals(HEX.formatHex(tx.getHash().toByteArray()))).findFirst().orElseThrow();
                require(receipt.getSuccessful() && CardanoRpcClient.integer(receipt.getFee()).equals(MpcCardanoTransactionCodec.fee(unsigned)),
                        "Cardano actual inclusion result or fee differs");
                require(receipt.getWitnesses().getVkeywitnessCount() == 1, "Cardano actual payment witness is absent/duplicated");
                var after = awaitBalance(rpc, recipient, unit, balance(recipientBefore, unit).add(amount));
                var sourceAfter = rpc.utxos(sourceAddress);
                BigInteger fee = outcome.feeLovelace();
                BigInteger nativePrincipal = MpcCardanoTransactionCodec.recipientLovelace(unsigned);
                require(balance(before, "lovelace").subtract(balance(sourceAfter, "lovelace")).equals(nativePrincipal.add(fee)),
                        "Cardano payer native delta differs from recipient ADA plus actual fee");
                if (!unit.equals("lovelace")) require(balance(before, unit).subtract(balance(sourceAfter, unit)).equals(amount),
                        "Cardano source token delta differs from the exact principal");
                for (String other : List.of("USDT", "USDC")) {
                    String otherUnit = funded.path("units").path(other).asText();
                    if (!otherUnit.equals(unit)) require(balance(before, otherUnit).equals(balance(sourceAfter, otherUnit)),
                            "Cardano owned change lost an unrelated asset");
                }
                totalFee = totalFee.add(fee);
                Files.writeString(Path.of("/tmp/surprising-wallet-mpc-cardano-native-" + symbol + "-" + pairIndex + ".json"), JSON.writeValueAsString(Map.of(
                        "unsigned", unsigned, "selected", selected, "blockBase64", java.util.Base64.getEncoder().encodeToString(block.toByteArray()),
                        "feeLovelace", fee, "sourceBefore", balance(before, "lovelace"), "sourceAfter", balance(sourceAfter, "lovelace"),
                        "recipientNativeDelta", balance(after, "lovelace").subtract(balance(recipientBefore, "lovelace")))));
                try { rpc.submit(HEX.parseHex(selected.transactionHex())); }
                catch (RuntimeException known) {
                    require(known.getMessage().matches("(?is).*(BadInputs|already|UTxO|utxo|inputs).*"), "Cardano replay failed for an unrelated reason: " + known.getMessage());
                }
                require(balance(sourceAfter, "lovelace").equals(balance(rpc.utxos(sourceAddress), "lovelace"))
                        && balance(after, unit).equals(balance(rpc.utxos(recipient), unit)), "Cardano same-wire replay paid principal or fee twice");
            }
            pairIndex++;
        }
        var sourceFinal = rpc.utxos(sourceAddress);
        var targetFinal = rpc.utxos(recipient);
        require(balance(initial, "lovelace").subtract(balance(sourceFinal, "lovelace"))
                .subtract(balance(targetFinal, "lovelace").subtract(balance(recipientInitial, "lovelace"))).equals(totalFee),
                "Cardano actual controlled native conservation differs from all nine fees");
        // A real delayed valid payment closes its TTL before submission. It is rejected without a mined fee.
        var expiryDraft = CardanoPublicTransactionBuilder.prepare(genesis, rpc.parameters(), sourceFinal, HEX.parseHex(key.publicKeyHex()),
                sourceAddress, recipient, "lovelace", BigInteger.valueOf(2_000_001), 30);
        doReturn(nodes.parties().subList(0, 2)).when(quorum).online();
        var expiryRequest = transaction.execute(status -> signing.reserveSignature(new MpcSignCommand(tenant, key.id(), key.activeEpoch(),
                ChainType.ADA, MpcSignCommand.BusinessType.DEV_FAUCET, "cardano-expiry-" + UUID.randomUUID(), "transaction",
                MpcSignCommand.Purpose.TRANSACTION, "CARDANO_PAYMENT_JSON", HEX.formatHex(JSON.writeValueAsBytes(expiryDraft.unsigned())),
                HEX.formatHex(MpcCardanoTransactionCodec.signingBytes(expiryDraft.unsigned()))), quorum.online()));
        signing.awaitSignature(tenant, expiryRequest.id(), quorum);
        var expired = MpcCardanoTransactionCodec.assemble(expiryDraft.unsigned(), signing.signature(tenant, expiryRequest.id()).orElseThrow());
        Sync.BlockRef expiryTip = null;
        for (int attempt = 0; attempt < 120; attempt++) {
            var current = rpc.tip();
            if (current.getSlot() >= MpcCardanoTransactionCodec.expiry(expiryDraft.unsigned())) { expiryTip = current; break; }
            Thread.sleep(250);
        }
        require(expiryTip != null, "Cardano private consensus did not close the original TTL");
        boolean rejected = false;
        try { rpc.submit(HEX.parseHex(expired.transactionHex())); }
        catch (RuntimeException failure) {
            require(failure.getMessage().matches("(?is).*(Expired|OutsideValidity|ValidityInterval|ttl).*"), "Cardano delayed payment failed for an unrelated reason: " + failure.getMessage());
            rejected = true;
        }
        require(rejected, "Cardano native consensus unexpectedly accepted an expired payment");
        Sync.BlockRef searched = expiryDraft.observed();
        for (int attempt = 0; attempt < 120; attempt++) {
            long safeHeight = rpc.tip().getHeight() - 3;
            if (safeHeight > searched.getHeight()) {
                var batch = rpc.blocksAfter(searched, safeHeight, 200);
                require(!batch.isEmpty(), "Cardano expiry search stopped before its stable end");
                for (var original : batch) {
                    var verified = CardanoExecutionReceipt.verifyBlock(original);
                    require(verified.transactions().stream().noneMatch(tx -> tx.hash().equals(expired.txHash())), "Cardano expired payment was actually included");
                    searched = verified.reference();
                }
            }
            if (searched.getSlot() >= MpcCardanoTransactionCodec.expiry(expiryDraft.unsigned())) break;
            Thread.sleep(250);
        }
        require(searched.getSlot() >= MpcCardanoTransactionCodec.expiry(expiryDraft.unsigned()), "Cardano expiry search did not cover a sufficiently confirmed closed TTL");
        var references = expiryDraft.unsigned().inputs().stream().map(input -> Query.TxoRef.newBuilder()
                .setHash(com.google.protobuf.ByteString.copyFrom(HEX.parseHex(input.transactionId()))).setIndex(input.index()).build()).toList();
        var unspent = rpc.readUtxos(references);
        var stable = rpc.block(searched);
        var expiry = CardanoExecutionReceipt.expiredUnspent(stable, expiryDraft.unsigned(), expired, CardanoRpcClient.point(searched), unspent, 3);
        require(expiry.feeLovelace().signum() == 0 && balance(sourceFinal, "lovelace").equals(balance(rpc.utxos(sourceAddress), "lovelace"))
                && balance(targetFinal, "lovelace").equals(balance(rpc.utxos(recipient), "lovelace")), "Cardano expired payment changed actual funds or charged a fee");
        Files.writeString(Path.of("/tmp/surprising-wallet-mpc-cardano-expired-unspent-public.json"), JSON.writeValueAsString(Map.of(
                "unsigned", expiryDraft.unsigned(), "selected", expired, "searched", CardanoRpcClient.point(searched),
                "blockBase64", java.util.Base64.getEncoder().encodeToString(stable.toByteArray()),
                "unspentBase64", java.util.Base64.getEncoder().encodeToString(unspent.toByteArray()))));
    }

    private static Sync.AnyChainBlock inclusion(CardanoRpcClient rpc, Sync.BlockRef start, String hash) throws Exception {
        var cursor = start;
        for (int attempt = 0; attempt < 80; attempt++) {
            var tip = rpc.tip();
            if (tip.getHeight() > cursor.getHeight()) {
                for (var block : rpc.blocksAfter(cursor, tip.getHeight(), 200)) {
                    if (block.getCardano().getBody().getTxList().stream().anyMatch(tx -> HEX.formatHex(tx.getHash().toByteArray()).equals(hash))) {
                        require(!block.getNativeBytes().isEmpty(), "Cardano native original block bytes absent");
                        var ref = CardanoRpcClient.reference(block.getCardano());
                        var fetched = rpc.block(ref);
                        require(fetched.equals(block), "Cardano fetched original block differs from streamed inclusion");
                        return block;
                    }
                    cursor = CardanoRpcClient.reference(block.getCardano());
                }
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Cardano actual MPC payment did not appear in native blocks");
    }
    private static Query.SearchUtxosResponse awaitBalance(CardanoRpcClient rpc, String address, String unit, BigInteger expected) throws Exception {
        for (int attempt = 0; attempt < 80; attempt++) {
            var snapshot = rpc.utxos(address);
            if (balance(snapshot, unit).equals(expected)) return snapshot;
            Thread.sleep(250);
        }
        throw new AssertionError("Cardano actual native UTxO balance differs for " + unit);
    }
    private static BigInteger balance(Query.SearchUtxosResponse snapshot, String unit) {
        BigInteger total = BigInteger.ZERO;
        for (var item : snapshot.getItemsList()) {
            if (unit.equals("lovelace")) total = total.add(CardanoRpcClient.integer(item.getCardano().getCoin()));
            else for (var group : item.getCardano().getAssetsList()) for (var asset : group.getAssetsList())
                if ((HEX.formatHex(group.getPolicyId().toByteArray()) + HEX.formatHex(asset.getName().toByteArray())).equals(unit))
                    total = total.add(CardanoRpcClient.integer(asset.getQuantity()));
        }
        return total;
    }
    private static JsonNode fixture(Path descriptor, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("node", Path.of("resources/infra/mpc-signer/tests/cardano_fixture.cjs").toRealPath().toString(), descriptor.toString()));
        command.addAll(Arrays.asList(arguments));
        var output = Files.createTempFile("surprising-wallet-mpc-cardano-public-", ".json");
        var log = Files.createTempFile("surprising-wallet-mpc-cardano-admin-", ".log");
        var process = new ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(log.toFile()).start();
        if (!process.waitFor(55, java.util.concurrent.TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Cardano isolated fixture timed out: " + log); }
        if (process.exitValue() != 0) throw new IllegalStateException("Cardano isolated fixture failed: " + Files.readString(log));
        return JSON.readTree(Files.readAllBytes(output));
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
