package com.surprising.wallet.custody;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.MpcNativeTestSupport;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;

/** Separate JVMs before and after a real PG/native-material restore; no private signing in Java. */
public final class MpcBackupRestoreNativeProbe {
    private final ObjectMapper json = new ObjectMapper();
    private Path root;
    private JdbcTemplate jdbc;
    private MpcNativeTestSupport support;
    private MpcSigningService signing;
    private WalletMpcKeyRepository keys;
    private WalletMpcKeyEpochRepository epochs;
    private WalletMpcSignAttemptRepository attempts;
    private int sequence;
    private final List<Map<String,Object>> witnesses = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !Set.of("prepare","restore").contains(args[1]))
            throw new IllegalArgumentException("private restore fixture directory and prepare|restore required");
        new MpcBackupRestoreNativeProbe().run(Path.of(args[0]),args[1]);
    }

    private void run(Path directory,String phase) throws Exception {
        root=directory.toRealPath();
        var ds=CustodyIntegrationDatabase.dataSource();
        if (phase.equals("prepare")) CustodyIntegrationDatabase.reset(ds);
        jdbc=new JdbcTemplate(ds);
        keys=new WalletMpcKeyRepository(jdbc);epochs=new WalletMpcKeyEpochRepository(jdbc,json);
        attempts=new WalletMpcSignAttemptRepository(jdbc,json);
        try (var nativeSupport=new MpcNativeTestSupport(jdbc)) {
            support=nativeSupport;
            byte[] master=Files.readAllBytes(root.resolve("coordinator-aes.bin"));
            assertEquals(32,master.length);signing=service(master);Arrays.fill(master,(byte)0);
            if (phase.equals("prepare")) prepare(); else restore();
        }
    }

    private MpcSigningService service(byte[] master) {
        var security=new CustodySecurityProperties();security.setSecretMasterKey(HexFormat.of().formatHex(master));
        return new MpcSigningService(new CustodyTenantTableRepository(jdbc),keys,epochs,support.requests,attempts,
                new CustodyCryptoService(security),json,support.manager, com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,json,support.manager));
    }

    private void prepare() throws Exception {
        var ethereum=support.address(ChainType.ETH,45100,0,"DEPOSIT");
        var solana=support.address(ChainType.SOLANA,45101,0,"DEPOSIT");
        int namespace=new CustodyTenantTableRepository(jdbc).findById(support.tenant).orElseThrow().derivationNamespace();
        var bip=keys.reserve(UUID.randomUUID(),support.tenant,"BTC",45102,namespace,0,"SCHNORR_BIP340");
        epochs.create(new MpcClient.Request(UUID.randomUUID(),support.tenant,bip.id(),1,MpcClient.Operation.CREATE_KEY,
                MpcClient.Algorithm.SCHNORR_BIP340,support.nodes.parties(),""));
        Instant deadline=Instant.now().plusSeconds(45);
        while (signing.requireKey(support.tenant,bip.id()).state()!=MpcKey.State.ACTIVE && Instant.now().isBefore(deadline)) {
            signing.advanceKey(support.tenant,bip.id(),support.nodes.clients());Thread.sleep(25);
        }
        var slots=List.of(new Slot(ethereum.getMpcKeyId(),ChainType.ETH),new Slot(solana.getMpcKeyId(),ChainType.SOLANA),new Slot(bip.id(),ChainType.BTC));
        var identities=new ArrayList<Map<String,Object>>();
        int number=0;
        for (var slot:slots) {
            var key=signing.requireKey(support.tenant,slot.id());assertEquals(MpcKey.State.ACTIVE,key.state());
            for (var pair:pairs()) sign(slot,1,pair,false);
            control("all",List.of());
            signing.reserveRefresh(support.tenant,key.id(),1,support.nodes.parties(),"restore-drill","preserve the current epoch across complete backup restore");
            deadline=Instant.now().plusSeconds(45);
            while (signing.requireKey(support.tenant,key.id()).state()!=MpcKey.State.ACTIVE && Instant.now().isBefore(deadline)) {
                signing.advanceRefresh(support.tenant,key.id(),2,support.nodes.clients());Thread.sleep(25);
            }
            assertEquals(2,signing.requireKey(support.tenant,key.id()).activeEpoch());
            sign(slot,2,pairs().get(number++),true);
            control("all",List.of());
            identities.add(Map.of("keyId",key.id(),"chain",slot.chain(),"algorithm",key.algorithm(),"publicKeyHex",key.publicKeyHex(),"epoch",2));
        }
        Files.writeString(root.resolve("backup-before-public.json"),json.writeValueAsString(Map.of("passed",true,"keys",identities,"witnesses",witnesses,
                "prepareJvmPid",ProcessHandle.current().pid(),"completedEncryptedResults",9,"completedUncollectedNativeResults",3,
                "addresses",Map.of("ETH",ethereum.getAddress(),"SOLANA",solana.getAddress()))));
        System.out.println("Backup prepare passed: three curves at epoch2,9 encrypted results and3 completed original uncollected SIGNs.");
    }

    private void restore() throws Exception {
        var before=json.readTree(Files.readAllBytes(root.resolve("backup-before-public.json")));
        assertNotEquals(before.path("prepareJvmPid").asLong(),ProcessHandle.current().pid());
        byte[] wrong=new byte[32];new java.security.SecureRandom().nextBytes(wrong);
        var wrongKey=service(wrong);Arrays.fill(wrong,(byte)0);
        int encrypted=0,pending=0;
        for (var witness:before.path("witnesses")) {
            var id=UUID.fromString(witness.path("requestId").asText());
            var attempt=UUID.fromString(witness.path("attemptId").asText());
            var original=support.requests.find(support.tenant,id).orElseThrow();
            assertEquals(attempt,original.currentAttemptId());
            if (witness.path("uncollected").asBoolean()) {
                assertNotEquals(MpcSignRequest.State.COMPLETED,original.state());assertTrue(signing.signature(support.tenant,id).isEmpty());
                var pair=new ArrayList<Integer>();for (var index:witness.path("pair")) pair.add(index.asInt());
                control("pair",pair);
                assertEquals(witness.path("signatureHex").asText(),HexFormat.of().formatHex(signing.awaitSignature(support.tenant,id,support.nodes)));pending++;
            } else {
                assertThrows(RuntimeException.class,()->wrongKey.signature(support.tenant,id));
                assertEquals(witness.path("signatureHex").asText(),HexFormat.of().formatHex(signing.signature(support.tenant,id).orElseThrow()));encrypted++;
            }
            assertEquals(attempt,support.requests.find(support.tenant,id).orElseThrow().currentAttemptId());
            assertEquals(1,attempts.history(support.tenant,id).size());
        }
        assertEquals(9,encrypted);assertEquals(3,pending);
        for (var identity:before.path("keys")) {
            var key=signing.requireKey(support.tenant,UUID.fromString(identity.path("keyId").asText()));
            assertEquals(2,key.activeEpoch());assertEquals(identity.path("publicKeyHex").asText(),key.publicKeyHex());
            assertEquals(MpcKeyEpoch.State.RETIRED,epochs.find(support.tenant,key.id(),1).orElseThrow().state());
            assertEquals(MpcKeyEpoch.State.ACTIVE,epochs.find(support.tenant,key.id(),2).orElseThrow().state());
            var slot=new Slot(key.id(),ChainType.valueOf(identity.path("chain").asText()));
            for (var pair:pairs()) sign(slot,2,pair,false);
        }
        control("all",List.of());
        assertEquals(21,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where payload_encoding='BACKUP_RESTORE_PUBLIC_PROBE' and state='COMPLETED'",Integer.class));
        for (var chain:List.of("ETH","SOLANA")) {
            String address=before.path("addresses").path(chain).asText();
            var retained=new ChainAddressRepository(jdbc).findEnabledByTenantAndAddress(support.tenant,chain,address).orElseThrow();
            var key=signing.requireKey(support.tenant,retained.getMpcKeyId());
            var profile=new ChainProfileRepository(jdbc).findAccountByChain(chain).orElseThrow();
            assertEquals(address,MpcPublicAddressCodec.encode(profile,HexFormat.of().parseHex(key.publicKeyHex())));
        }
        Files.writeString(root.resolve("backup-after-public.json"),json.writeValueAsString(Map.of("passed",true,"restoredJvmPid",ProcessHandle.current().pid(),
                "originalEncryptedResultsRecovered",encrypted,"originalUncollectedResultsRecovered",pending,"wrongApplicationKeyRejected",true,
                "freshSignatures",witnesses,"currentEpoch",2,"attemptsUnchanged",true)));
        System.out.println("Backup restore passed in a separate JVM:9 encrypted results,3 original pending results and9 new current-epoch signatures.");
    }

    private void sign(Slot slot,int epoch,List<Integer> pair,boolean uncollected) throws Exception {
        control("pair",pair);
        byte[] message=new byte[32];new java.security.SecureRandom().nextBytes(message);String hex=HexFormat.of().formatHex(message);
        var command=new MpcSignCommand(support.tenant,slot.id(),epoch,slot.chain(),MpcSignCommand.BusinessType.RECOVERY,"restore-drill-"+UUID.randomUUID(),
                "public-witness",MpcSignCommand.Purpose.TRANSACTION,"BACKUP_RESTORE_PUBLIC_PROBE",hex,hex);
        var request=new TransactionTemplate(support.manager).execute(status->signing.reserveSignature(command,pair.stream().map(support.nodes.parties()::get).toList()));
        assertNotNull(request);
        String signature;
        if (uncollected) {
            var original=attempts.find(support.tenant,request.currentAttemptId()).orElseThrow().request();
            for (int index:pair) support.nodes.clients().get(index).submit(original);
            Instant deadline=Instant.now().plusSeconds(45);
            while (!pair.stream().allMatch(index->support.nodes.clients().get(index).result(original).state()==MpcClient.State.COMPLETED) && Instant.now().isBefore(deadline)) Thread.sleep(25);
            assertTrue(pair.stream().allMatch(index->support.nodes.clients().get(index).result(original).state()==MpcClient.State.COMPLETED));
            signature=support.nodes.clients().get(pair.getFirst()).result(original).signatureHex();
            assertNotNull(signature);assertTrue(signing.signature(support.tenant,request.id()).isEmpty());
            assertNull(attempts.find(support.tenant,request.currentAttemptId()).orElseThrow().signatureCiphertext());
        } else signature=HexFormat.of().formatHex(signing.awaitSignature(support.tenant,request.id(),support.nodes));
        witnesses.add(Map.of("keyId",slot.id(),"epoch",epoch,"pair",pair,"requestId",request.id(),"attemptId",request.currentAttemptId(),
                "messageHex",hex,"signatureHex",signature,"uncollected",uncollected));
    }

    private void control(String action,List<Integer> pair) throws Exception {
        int current=++sequence;Path temporary=root.resolve("backup-control-pending.json");
        Files.writeString(temporary,json.writeValueAsString(Map.of("sequence",current,"action",action,"pair",pair)));
        Files.move(temporary,root.resolve("backup-control.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        Instant deadline=Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(deadline)) {
            var ack=root.resolve("backup-control-ack.json");
            if (Files.isRegularFile(ack) && json.readTree(Files.readString(ack)).path("sequence").asInt()==current) return;
            Thread.sleep(25);
        }
        throw new AssertionError("backup fixture did not acknowledge actual holder control");
    }
    private static List<List<Integer>> pairs() {return List.of(List.of(0,1),List.of(0,2),List.of(1,2));}
    private record Slot(UUID id,ChainType chain) { }
}
