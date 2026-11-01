package com.surprising.wallet.custody;

import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.MpcSigningService;
import com.surprising.wallet.testutil.MpcNativeTestSupport;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual Java/PG18/three-holder refresh; only completed reply delivery is deliberately interrupted. */
public final class MpcRefreshNativeProbe {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private Path root;
    private MpcNativeTestSupport mpc;
    private MpcSigningService signing;
    private WalletMpcKeyRepository keys;
    private WalletMpcKeyEpochRepository epochs;
    private int sequence;
    private final List<Map<String,Object>> witnesses = new ArrayList<>();
    private final List<Map<String,Object>> refreshed = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("private native refresh fixture directory required");
        new MpcRefreshNativeProbe().run(Path.of(args[0]));
    }

    private void run(Path directory) throws Exception {
        root = directory.toRealPath();
        var ds = CustodyIntegrationDatabase.dataSource();
        CustodyIntegrationDatabase.reset(ds);
        jdbc = new JdbcTemplate(ds);
        keys = new WalletMpcKeyRepository(jdbc);
        epochs = new WalletMpcKeyEpochRepository(jdbc,json);
        try (var support = new MpcNativeTestSupport(jdbc)) {
            mpc = support; signing = mpc.signing;
            var ethereum = mpc.address(ChainType.ETH, 43100, 0, "DEPOSIT");
            var solana = mpc.address(ChainType.SOLANA, 43101, 0, "DEPOSIT");
            int namespace = new CustodyTenantTableRepository(jdbc).findById(mpc.tenant).orElseThrow().derivationNamespace();
            var bip340 = keys.reserve(UUID.randomUUID(),mpc.tenant,"BTC",43102,namespace,0,"SCHNORR_BIP340");
            epochs.create(new MpcClient.Request(UUID.randomUUID(),mpc.tenant,bip340.id(),1,MpcClient.Operation.CREATE_KEY,
                    MpcClient.Algorithm.SCHNORR_BIP340,mpc.nodes.parties(),""));
            Instant deadline = Instant.now().plusSeconds(45);
            while (signing.requireKey(mpc.tenant,bip340.id()).state() != MpcKey.State.ACTIVE && Instant.now().isBefore(deadline)) {
                signing.advanceKey(mpc.tenant,bip340.id(),mpc.nodes.clients()); Thread.sleep(25);
            }
            assertEquals(MpcKey.State.ACTIVE,signing.requireKey(mpc.tenant,bip340.id()).state());
            for (var slot : List.of(new Slot(ethereum.getMpcKeyId(),ChainType.ETH),new Slot(solana.getMpcKeyId(),ChainType.SOLANA),new Slot(bip340.id(),ChainType.BTC))) verify(slot);
            assertEquals(18,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where state='COMPLETED'",Integer.class));
            assertEquals(0,jdbc.queryForObject("select count(*) from wallet_mpc_sign_request where state<>'COMPLETED'",Integer.class));
            assertEquals(3,jdbc.queryForObject("select count(*) from wallet_mpc_key where state='ACTIVE' and active_epoch=2",Integer.class));
            assertEquals(3,jdbc.queryForObject("select count(*) from wallet_mpc_key_epoch where state='RETIRED' and epoch=1",Integer.class));
            assertEquals(3,jdbc.queryForObject("select count(*) from wallet_mpc_key_epoch where state='ACTIVE' and epoch=2 and refresh_actor='native-refresh-operator'",Integer.class));
            assertEquals(ethereum.getAddress(),jdbc.queryForObject("select address from chain_address where id=? and mpc_key_id=?",String.class,ethereum.getId(),ethereum.getMpcKeyId()));
            assertEquals(solana.getAddress(),jdbc.queryForObject("select address from chain_address where id=? and mpc_key_id=?",String.class,solana.getId(),solana.getMpcKeyId()));
            Files.writeString(root.resolve("refresh-java-public.json"),json.writeValueAsString(Map.of("passed",true,"keys",refreshed,
                    "witnesses",witnesses,"signatures",18,"lostCompletedReplyScenarios",3,"allHolderRestartRounds",3,
                    "oldSignaturesRecovered",true,"pendingSigningBlockedRefresh",true,"originalRefreshIdempotent",true)));
        }
    }

    private void verify(Slot slot) throws Exception {
        var key = signing.requireKey(mpc.tenant,slot.keyId());
        var originalSignatures = new LinkedHashMap<UUID,byte[]>();
        for (var pair : pairs()) sign(slot,key,1,pair,originalSignatures);
        control("all",List.of());
        assertEquals(MpcKey.State.REFRESHING,signing.reserveRefresh(mpc.tenant,key.id(),1,mpc.nodes.parties(),"native-refresh-operator","refresh actual isolated shares").state());
        var original = epochs.find(mpc.tenant,key.id(),2).orElseThrow().request();
        var hiddenReplies = mpc.nodes.clients().stream().map(client -> {
            var hidden = spy(client);
            doAnswer(call -> {
                var result = (MpcClient.Result) call.callRealMethod();
                if (call.getArgument(0,MpcClient.Request.class).operation()==MpcClient.Operation.REFRESH && result.state()==MpcClient.State.COMPLETED)
                    throw new MpcClient.CallException("deliberately lost actual completed refresh reply",null);
                return result;
            }).when(hidden).result(any(MpcClient.Request.class));
            return hidden;
        }).toList();
        Instant deadline = Instant.now().plusSeconds(45);
        boolean nativeReady = false;
        while (!nativeReady && Instant.now().isBefore(deadline)) {
            assertEquals(MpcKey.State.REFRESHING,signing.advanceRefresh(mpc.tenant,key.id(),2,hiddenReplies).state());
            nativeReady = mpc.nodes.clients().stream().allMatch(client -> client.result(original).state()==MpcClient.State.COMPLETED);
            if (!nativeReady) Thread.sleep(25);
        }
        assertTrue(nativeReady,"all original holders must really complete the accepted refresh");
        assertEquals(1,signing.requireKey(mpc.tenant,key.id()).activeEpoch());
        assertEquals(MpcKeyEpoch.State.PREPARING,epochs.find(mpc.tenant,key.id(),2).orElseThrow().state());
        assertThrows(IllegalStateException.class,() -> new TransactionTemplate(mpc.manager).execute(status -> signing.reserveSignature(command(slot,key,2),mpc.nodes.parties().subList(0,2))));
        control("restart",List.of());
        signing = new MpcSigningService(new CustodyTenantTableRepository(jdbc),keys,epochs,mpc.requests,
                new WalletMpcSignAttemptRepository(jdbc,json),mpc.crypto,json,mpc.manager);
        jdbc.update("update custody_tenant set status='SUSPENDED' where id=?",mpc.tenant);
        MpcKey active;
        try { active = signing.advanceRefresh(mpc.tenant,key.id(),2,mpc.nodes.clients()); }
        finally { jdbc.update("update custody_tenant set status='ACTIVE' where id=?",mpc.tenant); }
        assertEquals(MpcKey.State.ACTIVE,active.state()); assertEquals(2,active.activeEpoch()); assertEquals(key.publicKeyHex(),active.publicKeyHex());
        assertEquals(original,epochs.find(mpc.tenant,key.id(),2).orElseThrow().request());
        assertEquals(active,signing.reserveRefresh(mpc.tenant,key.id(),1,mpc.nodes.parties(),"native-refresh-operator","refresh actual isolated shares"));
        assertEquals(active,signing.advanceRefresh(mpc.tenant,key.id(),2,List.of()));
        assertTrue(epochs.find(mpc.tenant,key.id(),3).isEmpty());
        for (var entry : originalSignatures.entrySet()) assertArrayEquals(entry.getValue(),signing.signature(mpc.tenant,entry.getKey()).orElseThrow());
        for (var pair : pairs()) sign(slot,active,2,pair,new LinkedHashMap<>());
        refreshed.add(Map.of("keyId",key.id(),"algorithm",key.algorithm(),"publicKeyHex",key.publicKeyHex(),"epoch",2,"originalSessionId",original.sessionId()));
        control("all",List.of());
    }

    private void sign(Slot slot,MpcKey key,int epoch,List<Integer> pair,Map<UUID,byte[]> saved) throws Exception {
        control("pair",pair);
        var parties = pair.stream().map(mpc.nodes.parties()::get).toList();
        var command = command(slot,key,epoch);
        var request = new TransactionTemplate(mpc.manager).execute(status -> signing.reserveSignature(command,parties));
        if (epoch==1) assertThrows(IllegalStateException.class,() -> signing.reserveRefresh(mpc.tenant,key.id(),1,mpc.nodes.parties(),"native-refresh-operator","refresh actual isolated shares"));
        var signature = signing.awaitSignature(mpc.tenant,request.id(),mpc.nodes);
        saved.put(request.id(),signature);
        witnesses.add(Map.of("keyId",key.id(),"epoch",epoch,"pair",pair,"requestId",request.id(),"attemptId",request.currentAttemptId(),
                "messageHex",command.preimageHex(),"signatureHex",HexFormat.of().formatHex(signature)));
    }
    private MpcSignCommand command(Slot slot,MpcKey key,int epoch) {
        byte[] message = new byte[32];new java.security.SecureRandom().nextBytes(message);String hex=HexFormat.of().formatHex(message);
        return new MpcSignCommand(mpc.tenant,key.id(),epoch,slot.chain(),MpcSignCommand.BusinessType.RECOVERY,"refresh-probe-"+UUID.randomUUID(),
                "public-witness",MpcSignCommand.Purpose.TRANSACTION,"REFRESH_PUBLIC_PROBE",hex,hex);
    }
    private static List<List<Integer>> pairs() { return List.of(List.of(0,1),List.of(0,2),List.of(1,2)); }
    private void control(String action,List<Integer> pair) throws Exception {
        int current=++sequence;Path pending=root.resolve("refresh-control-pending.json");
        Files.writeString(pending,json.writeValueAsString(Map.of("sequence",current,"action",action,"pair",pair)));
        Files.move(pending,root.resolve("refresh-control.json"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        Instant deadline=Instant.now().plusSeconds(30);
        while(Instant.now().isBefore(deadline)) {
            Path ack=root.resolve("refresh-control-ack.json");
            if(Files.isRegularFile(ack) && json.readTree(Files.readString(ack)).path("sequence").asInt()==current)return;
            Thread.sleep(25);
        }
        throw new AssertionError("native refresh fixture did not acknowledge original holder control");
    }
    private record Slot(UUID keyId,ChainType chain) { }
}
