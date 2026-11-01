package com.surprising.wallet.custody;

import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec;
import com.surprising.wallet.testutil.CustodyTestSupport;
import com.surprising.wallet.testutil.MpcNativeTestSupport;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Real Java custody publication against three native Monero holders; no private wallet API in Java. */
public final class MpcMoneroAddressNativeProbe {
    private MpcMoneroAddressNativeProbe() { }
    public static void main(String[] arguments) throws Exception {
        var json=new ObjectMapper();var root=Path.of(arguments[0]);boolean restart=arguments[1].equals("restart");
        var config=json.readTree(Files.readAllBytes(root.resolve("java-probe.json")));
        var dataSource=CustodyIntegrationDatabase.dataSource();var jdbc=new JdbcTemplate(dataSource);
        if(jdbc.queryForObject("select to_regclass('public.wallet_mpc_key') is null",Boolean.class))CustodyIntegrationDatabase.reset(dataSource);
        UUID tenant=UUID.fromString(config.path("tenantId").asText());var tenants=new CustodyTenantTableRepository(jdbc);
        if(tenants.findById(tenant).isEmpty())tenants.insert(tenant,"native-monero-address","Native Monero address acceptance");
        var endpoint=java.net.URI.create(config.path("daemonRpcUrl").asText());require(endpoint.getHost().equals("127.0.0.1") && endpoint.getScheme().equals("http"),"isolated loopback daemon required");
        var credentials=json.readTree(Files.readAllBytes(Path.of(config.path("daemonCredentialFile").asText())));
        jdbc.update("update chain_profile set enabled=(network='regtest') where chain='XMR'");
        jdbc.update("update chain_asset set active=true where chain='XMR' and symbol='XMR'");
        jdbc.update("update chain_rpc_node set enabled=false where chain='XMR'");
        jdbc.update("""
                insert into chain_rpc_node(chain,network,environment,node_label,purpose,connection_type,rpc_url,auth_type,username,password,enabled)
                values ('XMR','regtest','mpc-test','native-monero-public','daemon','HTTP_JSON_RPC',?,'DIGEST',?,?,true)
                on conflict(chain,network,environment,purpose,node_label) do update set enabled=true,rpc_url=excluded.rpc_url,username=excluded.username,password=excluded.password
                """,endpoint.toString(),credentials.path("username").asText(),credentials.path("password").asText());
        UUID actor=UUID.nameUUIDFromBytes(("native-monero-address:"+tenant).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbc.update("""
                insert into custody_tenant_user(id,tenant_id,email,display_name,password_hash,role,status)
                values (?,?,'native-monero-address@example.test','Native Monero administrator','test-only','TENANT_ADMIN','ACTIVE') on conflict(id) do nothing
                """,actor,tenant);
        new CustodyTenantChainTableRepository(jdbc).setStatus(tenant,"XMR","ACTIVE",actor);
        var principal=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,actor,tenant,"native-monero-address","TENANT_ADMIN",Set.of("*"));
        try(var nativeKeys=new MpcNativeTestSupport(jdbc)) {
            require(nativeKeys.tenant.equals(tenant),"native tenant changed");
            var addresses=MpcSigningNativeProbe.addresses(jdbc,nativeKeys.signing,nativeKeys.nodes,nativeKeys.manager);
            var command=new CustodyAddressService.CreateAddressCommand("XMR","native-alice",0L,null,Map.of());
            String retainedPublic=null;
            if(!restart) {
                jdbc.execute("create function public.xmr_native_publish_reject() returns trigger language plpgsql as $$ begin if new.chain='XMR' then raise exception 'reject publication';end if;return new;end $$;create trigger xmr_native_publish_reject before insert on custody_address for each row execute function public.xmr_native_publish_reject()");
                boolean rejected=false;
                try{addresses.create(principal,command,"API","127.0.0.1");}
                catch(RuntimeException expected){rejected=true;}
                finally{jdbc.execute("drop trigger xmr_native_publish_reject on custody_address;drop function public.xmr_native_publish_reject()");}
                require(rejected,"native publication failure was not exercised");
                require(jdbc.queryForObject("select count(*) from wallet_mpc_key where tenant_id=? and state='ACTIVE' and key_group in ('XMR_ROOT','XMR')",Integer.class,tenant)==2,"native root/child did not complete before publication rollback");
                require(jdbc.queryForObject("select count(*) from chain_address where chain='XMR'",Integer.class)==0
                        && new ChainScanHeightRepository(jdbc).nativeState("XMR",MoneroScanAnchorService.SCANNER,false).isEmpty(),"native public address or anchor escaped rollback");
                retainedPublic=jdbc.queryForObject("select public_key_hex from wallet_mpc_key where tenant_id=? and key_group='XMR'",String.class,tenant);
            }
            var first=addresses.create(principal,command,"API","127.0.0.1");
            if(retainedPublic!=null)require(retainedPublic.equals(HexFormat.of().formatHex(MoneroPublicAddressCodec.decode(first.address()).publicKeys())),"failed publication regenerated native account");
            require(first.id().equals(addresses.create(principal,command,"API","127.0.0.1").id()),"native address replay changed identity");
            var second=addresses.create(principal,new CustodyAddressService.CreateAddressCommand("XMR","native-bob",0L,null,Map.of()),"API","127.0.0.1");
            var gasService=CustodyTestSupport.gas(jdbc,addresses);
            var gas=gasService.create(principal,new CustodyGasService.CreateGasAccountCommand("XMR"),"127.0.0.1");
            require(gas.id().equals(gasService.create(principal,new CustodyGasService.CreateGasAccountCommand("XMR"),"127.0.0.1").id()),"native Gas publication replay changed identity");
            var published=new ArrayList<String>(List.of(first.address(),second.address(),gas.address()));
            if(restart)published.add(addresses.create(principal,new CustodyAddressService.CreateAddressCommand("XMR","native-after-restart",0L,null,Map.of()),"API","127.0.0.1").address());
            require(new java.util.HashSet<>(published).size()==published.size(),"native Monero accounts are not isolated");
            for(String address:published)require(MoneroPublicAddressCodec.decode(address).subaddress(),"native child is not a Monero subaddress");
            UUID rootId=jdbc.queryForObject("select id from wallet_mpc_key where tenant_id=? and key_group='XMR_ROOT'",UUID.class,tenant);
            var epochs=new WalletMpcKeyEpochRepository(jdbc,json);
            var ids=jdbc.queryForList("select id from wallet_mpc_key where tenant_id=? and key_group in ('XMR_ROOT','XMR') order by key_group,id",UUID.class,tenant);
            require(ids.size()==published.size()+1,"native root or child was recreated");
            for(var id:ids) {
                var request=epochs.find(tenant,id,1).orElseThrow().request();
                require(request.algorithm()==MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,"native Monero used another curve protocol");
                String publicKey=jdbc.queryForObject("select public_key_hex from wallet_mpc_key where tenant_id=? and id=?",String.class,tenant,id);
                for(var client:nativeKeys.nodes.clients()) {
                    var result=client.result(request);require(result.state()==MpcClient.State.COMPLETED && result.publicKeyHex().equals(publicKey),"all original holders do not agree on persisted native key");
                }
            }
            var anchor=new ChainScanHeightRepository(jdbc).nativeState("XMR",MoneroScanAnchorService.SCANNER,false).orElseThrow();
            require(anchor.genesisHash().equals(config.path("genesisHash").asText()),"published native scan genesis differs");
            var profile=new ChainProfileRepository(jdbc).findAccountByChain("XMR").orElseThrow();
            var prepared=CustodyTestSupport.moneroAnchors(jdbc).prepare(profile);
            var owner=new ChainAddressRepository(jdbc).listMpcAddresses("XMR").getFirst();
            boolean transactionRejected=false;
            try {
                new org.springframework.transaction.support.TransactionTemplate(nativeKeys.manager).execute(status->
                        nativeKeys.signing.observeMonero(tenant,owner.getMpcKeyId(),owner.getAddress(),0,prepared,null,nativeKeys.nodes));
            } catch(IllegalStateException expected){transactionRejected=true;}
            require(transactionRejected,"Monero observation was admitted inside a database transaction");
            MpcSigningService.MoneroSnapshot observation;
            tenants.updateStatus(tenant,"SUSPENDED");
            try {observation=nativeKeys.signing.observeMonero(tenant,owner.getMpcKeyId(),owner.getAddress(),0,prepared,null,nativeKeys.nodes);}
            finally {tenants.updateStatus(tenant,"ACTIVE");}
            require(observation.request().online().size()==2 && observation.result().monero().path("address").asText().equals(owner.getAddress())
                    && observation.result().monero().path("outputs").isEmpty()
                    && observation.result().monero().path("balanceAtomic").asText().equals("0"),"native wallet service observation lost its two-holder account facts");
            Files.writeString(root.resolve("monero-address-public.json"),json.writeValueAsString(Map.of("tenantId",tenant,"rootKeyId",rootId,
                    "addresses",published,"genesisHash",anchor.genesisHash(),"safeHeight",anchor.safeHeight(),"restart",restart,"observation",observation)));
            System.out.println("Monero real native root/child, custody rollback/replay, atomic Gas and public daemon Digest passed; restart="+restart+", addresses="+published.size());
        }
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
