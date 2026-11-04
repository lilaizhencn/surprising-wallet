package com.surprising.wallet.custody;

import com.surprising.wallet.common.signing.MoneroSigningPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.common.chain.ChainScanPoint;
import com.surprising.wallet.chain.monero.MoneroDaemonClient;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.MpcNativeTestSupport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Actual durable Java native signing bridge; this is separate from withdrawal/collection business acceptance. */
public final class MpcMoneroPaymentNativeProbe {
    private MpcMoneroPaymentNativeProbe() { }
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();var root=Path.of(args[0]);String phase=args[1];int pair=Integer.parseInt(args[2]);
        var config=json.readTree(Files.readAllBytes(root.resolve("java-probe.json")));
        var test=json.readTree(Files.readAllBytes(root.resolve("payment-case-public.json")));
        var jdbc=new JdbcTemplate(CustodyIntegrationDatabase.dataSource());UUID tenant=UUID.fromString(config.path("tenantId").asText());
        var master=root.resolve("coordinator-aes.bin");
        require(!Files.isSymbolicLink(master) && Files.getPosixFilePermissions(master).equals(PosixFilePermissions.fromString("rw-------")),"protected fixture coordinator key required");
        byte[] secret=Files.readAllBytes(master);require(secret.length==32,"fixture coordinator key length differs");
        var security=new CustodySecurityProperties();security.setSecretMasterKey(HexFormat.of().formatHex(secret));Arrays.fill(secret,(byte)0);
        var properties=new MpcNodeProperties();properties.setCaFile(root.resolve("ca.pem"));properties.setCertificateFile(root.resolve("client.pem"));properties.setPrivateKeyFile(root.resolve("client.key"));
        int[][] order={{0,1,2},{0,2,1},{1,2,0}};var configured=new ArrayList<MpcNodeProperties.Node>();
        for(int index:order[pair]){var peer=config.path("peers").get(index);configured.add(new MpcNodeProperties.Node(peer.path("identity").asText(),URI.create("https://localhost:"+peer.path("port").asInt())));}
        properties.setNodes(configured);
        try(var support=new MpcNativeTestSupport(jdbc);var nodes=new MpcNodes(properties)) {
            var requests=new WalletMpcSignRequestRepository(jdbc);var attempts=new WalletMpcSignAttemptRepository(jdbc,json);
            var signing=new MpcSigningService(new CustodyTenantTableRepository(jdbc),new WalletMpcKeyRepository(jdbc),new WalletMpcKeyEpochRepository(jdbc,json),
                    requests,attempts,new CustodyCryptoService(security),json,support.manager, com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,json,support.manager));
            var source=new ChainAddressRepository(jdbc).findEnabledByTenantAndAddress(tenant,"XMR",test.path("source").asText()).orElseThrow();
            var broadcastFence=new HotWalletBroadcastFence(new CustodyTenantChainTableRepository(jdbc),
                    new CustodyHotWalletRepository(jdbc),new CustodyAddressRepository(jdbc),
                    new ChainAddressRepository(jdbc),new CollectionRecordRepository(jdbc),
                    new com.surprising.wallet.repository.CustodyHotWalletInventoryObservationRepository(jdbc),support.manager);
            String reference=test.path("reference").asText("monero-payment-bridge-v1-"+pair);var selectedFile=root.resolve("selected-payment-"+pair+"-public.json");
            var intent=requests.findBusiness(tenant,ChainType.XMR,MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction");
            if(Set.of("sign","admit","select","recover-select").contains(phase)) {
                var payload=new MoneroSigningPayload("regtest",config.path("genesisHash").asText(),source.getAddress(),test.path("recipient").asText(),
                        BigDecimal.ONE,BigDecimal.ZERO,new BigInteger("10000000000"),10);
                var key=signing.requireKey(tenant,source.getMpcKeyId());
                var command=new MpcSignCommand(tenant,key.id(),key.activeEpoch(),ChainType.XMR,MpcSignCommand.BusinessType.DEV_FAUCET,reference,"transaction",MpcSignCommand.Purpose.TRANSACTION,
                        MoneroSigningPayload.ENCODING,HexFormat.of().formatHex(json.writeValueAsBytes(payload)),payload.preimageHex(json));
                boolean selecting=phase.equals("select") || phase.equals("recover-select");
                var request=selecting?intent.orElseThrow():new TransactionTemplate(support.manager).execute(status->signing.reserveSignature(command,nodes.online()));
                if(phase.equals("admit")) {
                    var original=attempts.find(tenant,request.currentAttemptId()).orElseThrow().request();
                    Files.writeString(root.resolve("admitted-sign-request-public.json"),json.writeValueAsString(original));
                    new TransactionTemplate(support.manager).execute(status->attempts.start(tenant,request.currentAttemptId()));
                    for(String party:original.online())nodes.clients().stream().filter(client->client.node().partyId().equals(party)).findFirst().orElseThrow().submit(original);
                    require(requests.find(tenant,request.id()).orElseThrow().state()!=MpcSignRequest.State.COMPLETED
                            && attempts.find(tenant,request.currentAttemptId()).orElseThrow().signatureCiphertext()==null,
                            "coordinator already collected the deliberately uncollected signature result");
                    System.out.println("XMR Java native payment admitted; coordinator exits before collecting original results");return;
                }
                if(selecting)require(request.state()!=MpcSignRequest.State.COMPLETED
                        && attempts.find(tenant,request.currentAttemptId()).orElseThrow().signatureCiphertext()==null,
                        "coordinator result-loss fixture did not preserve the uncollected original request");
                if(phase.equals("recover-select")) {
                    long deadline=System.nanoTime()+java.time.Duration.ofSeconds(60).toNanos();
                    while(true) {
                        try{signing.awaitSignature(tenant,request.id(),nodes);break;}
                        catch(MpcPendingException pending){require(pending.reference().equals(request.id()) && System.nanoTime()<deadline,"original native material recovery did not finish");Thread.sleep(100);}
                    }
                } else signing.awaitSignature(tenant,request.id(),nodes);
                var selected=signing.selectedMonero(tenant,request.id());
                Files.writeString(selectedFile,json.writeValueAsString(Map.of("requestId",request.id(),"selected",selected,"recoveredUncollectedResult",selecting)));
                boolean transactionRejected=false;
                try{new TransactionTemplate(support.manager).execute(status->signing.relayMonero(tenant,request.id(),selected.transactionHash(),nodes,broadcastFence));}
                catch(IllegalStateException expected){transactionRejected=true;}
                require(transactionRejected,"native relay was admitted inside a database transaction");
                boolean rejected=false;try{signing.relayMonero(tenant,request.id(),"00".repeat(32),nodes,broadcastFence);}catch(IllegalStateException expected){rejected=true;}
                require(rejected,"changed relay hash was not rejected before any native relay");
            } else if(phase.equals("hold")) {
                var request=intent.orElseThrow();var current=signing.advanceSignature(tenant,request.id(),nodes.clients());
                require(current.state()==MpcSignRequest.State.INDETERMINATE && current.currentAttemptId().equals(request.currentAttemptId())
                        && attempts.find(tenant,request.currentAttemptId()).orElseThrow().signatureCiphertext()==null
                        && attempts.history(tenant,request.id()).size()==1 && signing.signature(tenant,request.id()).isEmpty(),
                        "missing native material did not preserve the original unknown attempt without signature");
                Files.writeString(root.resolve("held-payment-public.json"),json.writeValueAsString(Map.of("requestId",request.id(),"attemptId",request.currentAttemptId(),"state",current.state(),"attemptCount",1)));
            } else {
                var request=intent.orElseThrow();var prior=json.readTree(Files.readAllBytes(selectedFile));var selected=signing.selectedMonero(tenant,request.id());
                require(prior.path("requestId").asText().equals(request.id().toString())
                        && json.treeToValue(prior.path("selected"),com.surprising.wallet.chain.monero.MoneroSignedPayment.class).equals(selected),
                        "Java restart changed the original encrypted artifact selection");
                require(jdbc.queryForObject("select count(*) from wallet_mpc_sign_attempt where tenant_id=? and request_id=?",Integer.class,tenant,request.id())==1,
                        "native recovery created a new signing attempt");
                if(phase.equals("relay")) {
                    var receipt=signing.relayMonero(tenant,request.id(),selected.transactionHash(),nodes,broadcastFence);
                    require(receipt.request().online().size()==1 && selected.originalHolders().contains(receipt.request().online().getFirst())
                            && receipt.result().monero().path("transactionHash").asText().equals(selected.transactionHash()),"native single-holder relay changed its parent transaction");
                } else if(phase.equals("observe")) {
                    var daemon=new MoneroDaemonClient(new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc),"mpc-test",2),json);
                    var network=daemon.network("regtest");var header=daemon.header("regtest",network.height()-1);
                    var anchor=new MoneroScanAnchorService.Anchor("regtest",network.genesisHash(),new ChainScanPoint(header.height(),header.height(),header.hash()),header.parentHash());
                    var observation=signing.observeMonero(tenant,source.getMpcKeyId(),source.getAddress(),Math.max(0,header.height()-1000),anchor,selected.transactionHash(),nodes);
                    var tx=observation.result().monero().path("transaction");
                    require(tx.path("known").asBoolean() && !tx.path("inPool").asBoolean() && !tx.path("doubleSpendSeen").asBoolean()
                            && !tx.path("coinbase").asBoolean() && tx.path("confirmations").asLong()>=10,"native original transaction has no canonical confirmation");
                    BigInteger fees=BigInteger.ZERO;
                    for(int index=0;index<=pair;index++)fees=fees.add(new BigInteger(json.readTree(Files.readAllBytes(root.resolve("selected-payment-"+index+"-public.json"))).path("selected").path("feeAtomic").asText()));
                    var expected=new BigInteger("5000000000000").subtract(BigInteger.valueOf(pair+1L).multiply(new BigInteger("1000000000000"))).subtract(fees);
                    require(observation.result().monero().path("balanceAtomic").asText().equals(expected.toString())
                            && observation.result().monero().path("unlockedBalanceAtomic").asText().equals(expected.toString()),"durable Java native payment principal/fee do not conserve original funds");
                    var original=attempts.find(tenant,request.currentAttemptId()).orElseThrow().request();
                    for(String party:selected.originalHolders()) {
                        var client=nodes.clients().stream().filter(value->value.node().partyId().equals(party)).findFirst().orElseThrow();
                        require(client.result(original).state()==com.surprising.wallet.common.signing.MpcClient.State.COMPLETED,"native original SIGN receipt did not survive process/private-wallet restart");
                    }
                } else throw new IllegalArgumentException("unknown native payment bridge phase");
            }
            System.out.println("XMR actual durable Java native payment bridge passed: "+phase+", pair="+pair);
        }
    }
    private static void require(boolean valid,String message){if(!valid)throw new AssertionError(message);}
}
