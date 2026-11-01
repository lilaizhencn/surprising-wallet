package com.surprising.wallet.custody;

import com.surprising.wallet.chain.model.ChainScanPoint;
import com.surprising.wallet.chain.monero.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.model.mpc.MpcNodeProperties;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** Actual Java production scanner, PG18 posting and two independent native holders. */
public final class MpcMoneroDepositNativeProbe {
    private MpcMoneroDepositNativeProbe() { }
    public static void main(String[] args) throws Exception {
        var root=Path.of(args[0]);String phase=args[1];int pair=Integer.parseInt(args[2]);var json=new ObjectMapper();
        var config=json.readTree(Files.readAllBytes(root.resolve("java-probe.json")));
        var test=json.readTree(Files.readAllBytes(root.resolve("deposit-case-public.json")));
        var publication=json.readTree(Files.readAllBytes(root.resolve("monero-address-public.json")));
        var jdbc=new JdbcTemplate(CustodyIntegrationDatabase.dataSource());UUID tenant=UUID.fromString(config.path("tenantId").asText());
        var properties=new MpcNodeProperties();properties.setCaFile(root.resolve("ca.pem"));properties.setCertificateFile(root.resolve("client.pem"));properties.setPrivateKeyFile(root.resolve("client.key"));
        int[][] orders={{0,1,2},{0,2,1},{1,2,0}};var peers=new ArrayList<MpcNodeProperties.Node>();
        for(int index:orders[pair]) {var peer=config.path("peers").get(index);peers.add(new MpcNodeProperties.Node(peer.path("identity").asText(),URI.create("https://localhost:"+peer.path("port").asInt())));}
        properties.setNodes(peers);
        try(var nativeKeys=new MpcNativeTestSupport(jdbc);var nodes=new MpcNodes(properties);
            var context=DepositTestSupport.context(jdbc,CustodyTestSupport.events(jdbc),json)) {
            var profiles=new ChainProfileRepository(jdbc);var owners=new ChainAddressRepository(jdbc);
            var heights=new ChainScanHeightRepository(jdbc);var blocks=new ChainScanBlockRepository(jdbc);
            var daemon=new MoneroDaemonClient(new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2),json);
            var scanner=new MoneroDepositScanner(daemon,nativeKeys.signing,org.mockito.Mockito.mock(MoneroSubmissionService.class),nodes,profiles,new WalletMpcKeyEpochRepository(jdbc,json),owners,
                    heights,blocks,new DepositRecordRepository(jdbc),context.getBean(DepositService.class),nativeKeys.manager,json,
                    new WalletRuntimeConfigService(profiles,new WalletSystemConfigRepository(jdbc)));
            String tx=test.path("transactionHash").asText();int expected=test.path("outputs").size();
            if(phase.equals("locked")) {
                jdbc.update("update chain_profile set enabled=(network='regtest'),scan_enabled=true,deposit_confirmations=1,scan_max_blocks_per_run=1000 where chain='XMR'");
                require(scanner.scanAndCredit(profiles.findAccountByChain("XMR").orElseThrow()).isEmpty(),"immature native output was credited");
                require(jdbc.queryForObject("select count(*) from deposit_record where chain='XMR' and tx_hash=? and credited=false and confirmations=1 and status='CONFIRMING'",Integer.class,tx)==expected,
                        "actual immature outputs/depth were not durably recorded");
                require(balance(jdbc,tenant).compareTo(new BigDecimal(test.path("previousFundedAtomic").asText()).movePointLeft(12))==0,"locked native output changed the booked principal");
            } else if(phase.equals("unlock")) {
                suspend(jdbc,tenant,true);long original=heights.nativeState("XMR",MoneroScanAnchorService.SCANNER,false).orElseThrow().safeHeight();
                if(test.path("index").asInt()==0) {
                    jdbc.execute("create function xmr_native_deposit_reject() returns trigger language plpgsql as $$ begin raise exception 'reject projection';end $$;create trigger xmr_native_deposit_reject before insert on custody_deposit for each row execute function xmr_native_deposit_reject()");
                    boolean rejected=false;
                    try{scanner.confirmObserved();}catch(RuntimeException error){rejected=true;}
                    finally{jdbc.execute("drop trigger xmr_native_deposit_reject on custody_deposit;drop function xmr_native_deposit_reject()");}
                    require(rejected && balance(jdbc,tenant).signum()==0 && jdbc.queryForObject("select count(*) from deposit_record where credited=true",Integer.class)==0,
                            "native projection failure escaped the original posting transaction");
                }
                require(scanner.confirmObserved()==expected && scanner.confirmObserved()==0,"actual native unlock was not posted exactly once");
                require(heights.nativeState("XMR",MoneroScanAnchorService.SCANNER,false).orElseThrow().safeHeight()==original,"pending output refresh changed the new-block cursor");
                require(balance(jdbc,tenant).compareTo(new BigDecimal(test.path("fundedAtomic").asText()).movePointLeft(12))==0,"actual native funded principal differs from the booked sum");
                require(jdbc.queryForObject("select count(*) from deposit_record where tx_hash=? and credited=true and confirmations>=10 and canonical_status='CANONICAL'",Integer.class,tx)==expected,
                        "native output credit lost its original confirmation and canonical state");
                suspend(jdbc,tenant,false);
            } else if(phase.equals("reorg")) {
                suspend(jdbc,tenant,true);
                require(scanner.reconcileObserved()==expected && scanner.reconcileObserved()==0,"native canonical rollback did not reverse the original credits exactly once");
                require(balance(jdbc,tenant).compareTo(new BigDecimal(test.path("previousFundedAtomic").asText()).movePointLeft(12))==0,"native reorg principal was not reversed");
                require(jdbc.queryForObject("select count(*) from deposit_record where tx_hash=? and credited=false and canonical_status='REORGED'",Integer.class,tx)==expected,
                        "orphaned native output retained its canonical credit");
                var gas=owners.listMpcAddresses("XMR").stream().filter(row->row.getAddress().equals(publication.path("addresses").get(2).asText())).findFirst().orElseThrow();
                var state=heights.nativeState("XMR",MoneroScanAnchorService.SCANNER,false).orElseThrow();var header=daemon.header("regtest",state.safeHeight());
                var anchor=new MoneroScanAnchorService.Anchor("regtest",state.genesisHash(),new ChainScanPoint(header.height(),header.height(),header.hash()),header.parentHash());
                var observed=nativeKeys.signing.observeMonero(tenant,gas.getMpcKeyId(),gas.getAddress(),0,anchor,null,nodes);
                require(observed.result().monero().path("balanceAtomic").asText().equals("0"),"native private wallet retained the orphaned Gas funding");
            } else throw new IllegalArgumentException("unknown native deposit phase");
            require(jdbc.queryForObject("select count(*) from chain_rpc_node where chain='XMR' and enabled=true and purpose<>'daemon'",Integer.class)==0,
                    "Java native deposit acceptance had a private-wallet RPC route");
            var state=heights.nativeState("XMR",MoneroScanAnchorService.SCANNER,false).orElseThrow();
            Files.writeString(root.resolve("monero-deposit-public.json"),json.writeValueAsString(Map.of("phase",phase,"pair",pair,"tenantId",tenant,
                    "transactionHash",tx,"safeHeight",state.safeHeight(),"bookedXmr",balance(jdbc,tenant).toPlainString(),
                    "creditedOutputs",jdbc.queryForObject("select count(*) from deposit_record where chain='XMR' and credited=true",Integer.class),
                    "reorgedOutputs",jdbc.queryForObject("select count(*) from deposit_record where chain='XMR' and canonical_status='REORGED'",Integer.class))));
            System.out.println("XMR actual Java scanner/native holders/PG18 posting passed: phase="+phase+", pair="+pair+", original outputs="+expected);
        }
    }
    private static void suspend(JdbcTemplate jdbc,UUID tenant,boolean suspended) {
        jdbc.update("update chain_profile set enabled=?,scan_enabled=? where chain='XMR' and network='regtest'",!suspended,!suspended);
        jdbc.update("update custody_tenant set status=? where id=?",suspended?"SUSPENDED":"ACTIVE",tenant);
        jdbc.update("update chain_address set enabled=? where tenant_id=? and chain='XMR'",!suspended,tenant);
    }
    private static BigDecimal balance(JdbcTemplate jdbc,UUID tenant) {
        return jdbc.queryForObject("select coalesce(sum(total_balance),0) from ledger_balance where tenant_id=? and chain='XMR'",BigDecimal.class,tenant);
    }
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
