package com.surprising.wallet.custody;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.chain.hypercore.*;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.*;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.model.*;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import com.surprising.wallet.testutil.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.*;
import tools.jackson.databind.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Funded official-testnet acceptance. Existing funded native identities and the test database must survive retries. */
public final class MpcHyperCoreFundedNativeProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "https://api.hyperliquid-testnet.xyz";
    private MpcHyperCoreFundedNativeProbe() { }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement(proxyTargetClass=true) static class Transactions { }

    public static void main(String[] arguments) throws Exception {
        Path root = Path.of(arguments[0]).toRealPath();
        var meta = JSON.readTree(Files.readAllBytes(root.resolve("acceptance-public.json")));
        require(meta.path("network").asText().equals("hyperliquid-testnet") && meta.path("endpoint").asText().equals(ENDPOINT), "funded acceptance requires official testnet");
        String recipient = arguments[1].toLowerCase(Locale.ROOT);
        require(recipient.matches("0x[0-9a-f]{40}"), "activated external testnet recipient required");
        int selectedRound = Integer.parseInt(arguments[2]);
        String phase = Objects.requireNonNullElse(System.getenv("MPC_HYPERCORE_FUNDED_PHASE"), "baseline");
        require(Set.of("baseline", "recovery", "collection", "owned-withdrawal", "procurement", "hype-withdrawal", "hype-collection", "hype-owned-withdrawal").contains(phase), "unknown funded acceptance phase");
        String runId = System.getenv("MPC_HYPERCORE_FUNDED_RUN_ID");
        require(runId != null && runId.matches("[a-z0-9][a-z0-9-]{0,47}"), "explicit stable funded test run ID required");
        String payerRole = System.getenv("MPC_HYPERCORE_FUNDED_PAYER_ROLE");
        require(Set.of("source", "collector").contains(Objects.requireNonNullElse(payerRole, "")), "original funded payer role required");
        String collectorRole = payerRole.equals("source") ? "collector" : "source";
        var onlineIndices = List.of(List.of(0,1), List.of(0,2), List.of(1,2)).get(selectedRound);
        var ds = CustodyIntegrationDatabase.dataSource();
        var jdbc = new JdbcTemplate(ds);
        if (jdbc.queryForObject("select to_regclass('public.wallet_mpc_key') is null", Boolean.class)) CustodyIntegrationDatabase.reset(ds);
        UUID tenant = UUID.fromString(meta.path("tenantId").asText());
        var manager = new DataSourceTransactionManager(ds); var transaction = new TransactionTemplate(manager);
        var tenants = new CustodyTenantTableRepository(jdbc); var keys = new WalletMpcKeyRepository(jdbc);
        var epochs = new WalletMpcKeyEpochRepository(jdbc, JSON); var requests = new WalletMpcSignRequestRepository(jdbc);
        var attempts = new WalletMpcSignAttemptRepository(jdbc, JSON); var balances = new LedgerBalanceRepository(jdbc);
        var security = new CustodySecurityProperties();
        security.setSecretMasterKey(HexFormat.of().formatHex(Files.readAllBytes(root.resolve("coordinator.aes"))));
        var crypto = new CustodyCryptoService(security); crypto.validateConfiguration();
        var signing = new MpcSigningService(tenants, keys, epochs, requests, attempts, crypto, JSON, manager, com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,JSON,manager));
        var properties = new MpcNodeProperties(); properties.setCaFile(root.resolve("ca.pem"));
        properties.setCertificateFile(root.resolve("client.pem")); properties.setPrivateKeyFile(root.resolve("client.key"));
        var configured = new ArrayList<MpcNodeProperties.Node>();
        for (var peer : meta.path("peers")) configured.add(new MpcNodeProperties.Node(peer.path("identity").asText(), URI.create("https://localhost:" + peer.path("port").asInt())));
        properties.setNodes(configured); properties.setOperationTimeout(Duration.ofSeconds(60));
        try (var nodes = new MpcNodes(properties)) {
            jdbc.update("update chain_profile set enabled=(network='testnet'),withdraw_enabled=true,collection_enabled=true,transfer_enabled=true where chain='HYPERCORE'");
            jdbc.update("update chain_rpc_node set enabled=false where chain='HYPERCORE'");
            for (String purpose : List.of("info", "exchange")) jdbc.update("""
                insert into chain_rpc_node(chain,network,environment,node_label,purpose,rpc_url,enabled)
                values ('HYPERCORE','testnet','mpc-funded','official-testnet',?,?,true)
                on conflict(chain,network,environment,purpose,node_label) do update set enabled=true,rpc_url=excluded.rpc_url
                """, purpose, ENDPOINT);
            var profiles = new ChainProfileRepository(jdbc); var profile = profiles.findAccountByChain("HYPERCORE").orElseThrow();
            require(profile.getNetwork().equals("testnet"), "mainnet actions are forbidden in funded acceptance");
            var realApi = new HyperCoreApiClient(JSON, profiles, new ChainRpcNodeService(new ChainRpcNodeRepository(jdbc), "mpc-funded", 2));
            var addresses = new ChainAddressRepository(jdbc); var custody = new CustodyAddressRepository(jdbc);
            var subjects = new CustodyDerivationSubjectRepository(jdbc); int namespace = tenants.findById(tenant).orElseThrow().derivationNamespace();
            var publisher = MpcSigningNativeProbe.addresses(jdbc, signing, nodes, manager);
            UUID actor = UUID.nameUUIDFromBytes(("hypercore-funded-acceptance:" + tenant).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jdbc.update("""
                insert into custody_tenant_user(id,tenant_id,email,display_name,password_hash,role,status)
                values (?,?,'hypercore-funded-acceptance@example.test','Funded native acceptance','test-only','TENANT_ADMIN','ACTIVE')
                on conflict(id) do nothing
                """, actor, tenant);
            var principal = new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER, actor, tenant, "funded-native-acceptance", "TENANT_ADMIN", Set.of("*"));
            new CustodyTenantChainTableRepository(jdbc).setStatus(tenant, "HYPERCORE", "ACTIVE", principal.actorId());
            var owned = new LinkedHashMap<String, ChainAddressRecord>();
            var ownedCustody = new LinkedHashMap<String, UUID>();
            for (String role : List.of("source", "collector")) {
                var key = meta.path("keys").path(role); UUID id = UUID.fromString(key.path("keyId").asText());
                String subject = "funded-hypercore-" + role; int user = transaction.execute(status -> subjects.resolve(tenant, subject));
                long index = key.path("addressIndex").asLong();
                var creation = JSON.readValue(JSON.writeValueAsBytes(key.path("creationRequest")), MpcClient.Request.class);
                for (int onlineIndex : onlineIndices) {
                    var client = nodes.clients().get(onlineIndex);
                    var result = client.result(creation);
                    require(result.state() == MpcClient.State.COMPLETED && result.publicKeyHex().equals(key.path("publicKeyHex").asText()), "persistent funded key creation receipt differs");
                }
                transaction.executeWithoutResult(status -> {
                    var imported = keys.reserve(id, tenant, "HYPERCORE", Integer.toUnsignedLong(user), namespace, index, "ECDSA_SECP256K1");
                    require(imported.id().equals(id), "funded identity was replaced by a new key");
                    epochs.create(creation);
                    if (imported.state() == MpcKey.State.CREATING) {
                        epochs.observations(tenant, id, 1, MpcKeyEpoch.State.ACTIVE, Map.of());
                        require(keys.activate(tenant, id, 1, key.path("publicKeyHex").asText()) == 1, "funded public identity could not be imported");
                    }
                });
                var view = publisher.create(principal, new CustodyAddressService.CreateAddressCommand("HYPERCORE", subject, index, role, Map.of("nativeAcceptance", true)), "API", "127.0.0.1");
                require(view.address().equalsIgnoreCase(key.path("address").asText()), "published funded address differs from native joint key");
                owned.put(role, addresses.findOwned(tenant, custody.require(tenant, view.id()).chainAddressId()).orElseThrow());
                ownedCustody.put(role, view.id());
            }
            var orders = new WithdrawalOrderRepository(jdbc); var actions = new HyperCoreActionRepository(jdbc);
            var collections = new CollectionRecordRepository(jdbc);
            var api = spy(realApi); var quorum = spy(nodes); doNothing().when(quorum).close();
            var current = new AtomicReference<HyperCoreSubmissionService.Operation>();
            var submissionsSent = new AtomicInteger();
            doAnswer(call -> {
                require(!TransactionSynchronizationManager.isActualTransactionActive(), "HyperCore public read held a business database transaction");
                return call.callRealMethod();
            }).when(api).postInfo(any());
            doAnswer(call -> {
                require(!TransactionSynchronizationManager.isActualTransactionActive(), "funded HyperCore RPC held a database transaction");
                var op = Objects.requireNonNull(current.get()); var request = requests.findBusiness(tenant, ChainType.HYPERCORE, MpcSignCommand.BusinessType.valueOf(op.kind().name()), op.reference(), "transaction").orElseThrow();
                String boundId = op.kind() == HyperCoreSubmissionService.Kind.WITHDRAWAL ? orders.find("HYPERCORE", op.reference(), tenant).orElseThrow().getTxHash()
                    : collections.findTxHash(tenant, "HYPERCORE", op.reference()).orElseThrow();
                require(request.state() == MpcSignRequest.State.COMPLETED && boundId != null
                    && actions.find(boundId).orElseThrow().raw().equals(call.getArgument(0).toString()), "funded RPC preceded durable signature/body/business ownership");
                submissionsSent.incrementAndGet();
                var result = (JsonNode) call.callRealMethod();
                Files.writeString(root.resolve("funded-requests-public.jsonl"), JSON.writeValueAsString(Map.of("reference", op.reference(), "actionId", boundId,
                    "request", call.getArgument(0), "response", result, "observedAt", Instant.now().toString())) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                if (phase.equals("recovery")) throw new IllegalStateException("acceptance intentionally lost every real exchange response");
                return result;
            }).when(api).postExchange(any());
            try (var deposits = DepositTestSupport.context(jdbc, CustodyTestSupport.events(jdbc), JSON); var context = new AnnotationConfigApplicationContext()) {
                context.register(Transactions.class); context.registerBean(PlatformTransactionManager.class, () -> manager);
                context.registerBean(WithdrawalOrderRepository.class, () -> orders); context.registerBean(CollectionRecordRepository.class, () -> collections);
                context.registerBean(HyperCoreActionRepository.class, () -> actions); context.registerBean(HyperCoreTokenMetadataRepository.class, () -> new HyperCoreTokenMetadataRepository(jdbc));
                context.registerBean(AccountSequenceRepository.class, () -> new AccountSequenceRepository(jdbc)); context.registerBean(WalletMpcSignRequestRepository.class, () -> requests);
                context.registerBean(MpcSigningService.class, () -> signing); context.registerBean(MpcNodes.class, () -> quorum); context.registerBean(ChainProfileRepository.class, () -> profiles);
                context.registerBean(ChainAddressRepository.class, () -> addresses); context.registerBean(TokenConfigRepository.class, () -> new TokenConfigRepository(jdbc));
                context.registerBean(ObjectMapper.class, () -> JSON); context.registerBean(LedgerBalanceRepository.class, () -> balances); context.registerBean(WithdrawalSettlementService.class);
                context.registerBean(HyperCoreSubmissionService.class); context.registerBean(HyperCoreApiClient.class, () -> api);
                context.registerBean(HyperCoreLedgerCursorRepository.class, () -> new HyperCoreLedgerCursorRepository(jdbc));
                context.registerBean(DepositService.class, () -> deposits.getBean(DepositService.class)); context.registerBean(HyperCoreDepositService.class);
                context.registerBean(WalletRuntimeConfigService.class, () -> RuntimeTestSupport.database(jdbc)); context.registerBean(HyperCoreTransactionService.class); context.refresh();
                var scanner = spy(new HyperCoreDepositScanner(api, context.getBean(HyperCoreDepositService.class), new HyperCoreTokenMetadataRepository(jdbc), new HyperCoreSpotAssetRepository(jdbc), addresses));
                scanner.scanAndCredit(profile);
                // Metadata was just fetched from the official network. Repeat-credit checks reuse it within this one phase.
                doNothing().when(scanner).syncSpotMetadata("testnet");
                if (phase.equals("procurement")) {
                    bookTestProcurement(root, realApi, jdbc, balances, transaction, tenant, owned.get("collector"), ownedCustody.get("collector"));
                    requirePhysicalConservation(realApi, balances, tenant, owned.values());
                    System.out.println("HyperCore test HYPE procurement: original MPC order/fills, exact USDC expense/HYPE receipt, atomic audit and total asset conservation passed.");
                    return;
                }
                var transfers = context.getBean(HyperCoreTransactionService.class); var submissions = context.getBean(HyperCoreSubmissionService.class);
                int round = selectedRound;
                for (var pair : List.of(onlineIndices)) {
                    doReturn(pair.stream().map(nodes.parties()::get).toList()).when(quorum).online();
                    boolean hype=phase.startsWith("hype-");String asset=hype?"HYPE":"USDC";
                    BigDecimal payment=hype?new BigDecimal(phase.equals("hype-owned-withdrawal")?"0.001":"0.01"):BigDecimal.ONE;
                    var source = owned.get(payerRole);
                    if (phase.equals("collection") || phase.equals("hype-collection")) {
                        String reference = "hypercore-funded-"+phase+"-"+runId+"-"+payerRole+"-" + round++;
                        var op = new HyperCoreSubmissionService.Operation(tenant, reference, HyperCoreSubmissionService.Kind.COLLECTION); current.set(op);
                        if (collections.findStatus(tenant,"HYPERCORE",reference).isEmpty()) {
                            require(collections.create(tenant, ownedCustody.get(payerRole), reference, "HYPERCORE", asset, source.getAddress(), owned.get(collectorRole).getAddress(), payment, BigDecimal.ZERO, null) == 1, "funded collection was not created");
                        }
                        if (!collections.findStatus(tenant,"HYPERCORE",reference).orElseThrow().equals("CONFIRMED")) {
                            if (submissions.intent(op).isPresent()) transfers.recover(op); else transfers.collect(tenant, reference, source);
                            String id = collections.findTxHash(tenant,"HYPERCORE",reference).orElseThrow();
                            require(awaitConfirmation(() -> transfers.confirmCollection(tenant,reference,id)), "funded collection did not settle");
                            require(!transfers.confirmCollection(tenant,reference,id), "funded collection settled twice");
                        }
                        int before = submissionsSent.get(); require(transfers.recover(op), "funded collection was not recoverable");
                        require(submissionsSent.get() == before, "confirmed collection was submitted again");
                        BigDecimal beforeScan=balances.find(tenant,"HYPERCORE",asset,owned.get(collectorRole).getAccountId()).map(value->value.getTotalBalance()).orElse(BigDecimal.ZERO);
                        scanner.scanAndCredit(profile);scanner.scanAndCredit(profile);
                        require(beforeScan.compareTo(balances.find(tenant,"HYPERCORE",asset,owned.get(collectorRole).getAccountId()).map(value->value.getTotalBalance()).orElse(BigDecimal.ZERO))==0,
                            "real internal collection was incorrectly credited as another customer deposit");
                        requirePhysicalConservation(realApi, balances, tenant, owned.values());
                        System.out.println("HyperCore funded "+asset+" collection quorum " + pair + ": actual owned transfer, exact original ledger proof, once settlement and total balance conservation passed.");
                        continue;
                    }
                    String destination=phase.endsWith("owned-withdrawal")?owned.get(collectorRole).getAddress():recipient;
                    String prefix=switch(phase) {
                        case "recovery" -> "hypercore-funded-usdc-lost-reply-";
                        case "owned-withdrawal" -> "hypercore-funded-owned-withdrawal-";
                        case "hype-withdrawal" -> "hypercore-funded-hype-withdrawal-";
                        case "hype-owned-withdrawal" -> "hypercore-funded-hype-owned-withdrawal-";
                        default -> "hypercore-funded-usdc-";
                    };
                    String reference=prefix+runId+"-"+payerRole+"-"+round++;
                    var op = new HyperCoreSubmissionService.Operation(tenant, reference, HyperCoreSubmissionService.Kind.WITHDRAWAL); current.set(op);
                    if (orders.find("HYPERCORE", reference, tenant).isEmpty()) transaction.executeWithoutResult(status -> {
                        orders.createForTenant(tenant, reference, source.getUserId(), "HYPERCORE", asset, source.getAddress(), source.getAccountId(), destination, payment, BigDecimal.ZERO);
                        require(balances.freeze("HYPERCORE", asset, source.getAccountId(), payment, tenant), "real funded principal could not be frozen");
                        orders.updateStatus(tenant, "HYPERCORE", reference, "FROZEN", source.getAddress(), null, null);
                        require(orders.claimSigningDurable(tenant, "HYPERCORE", reference, source.getAddress()) == 1, "funded order could not be claimed");
                    });
                    var boundOrder = orders.find("HYPERCORE", reference, tenant).orElseThrow();
                    require(boundOrder.getFromAddress().equals(source.getAddress()) && boundOrder.getToAddress().equals(destination)
                        && boundOrder.getAssetSymbol().equals(asset) && boundOrder.getAmount().compareTo(payment) == 0
                        && boundOrder.getFee().signum() == 0, "funded replay parameters differ from the original business");
                    if (!orders.find("HYPERCORE", reference, tenant).orElseThrow().getStatus().equals("CONFIRMED")) {
                        String id;
                        if (submissions.intent(op).isPresent()) { transfers.recover(op); id = orders.find("HYPERCORE", reference, tenant).orElseThrow().getTxHash(); }
                        else { id = transfers.send(op, source); submissions.restoreSent(op, id); }
                        require(awaitConfirmation(() -> transfers.confirmWithdrawal(tenant, reference, id)), "funded withdrawal did not settle");
                        require(!transfers.confirmWithdrawal(tenant, reference, id), "funded withdrawal settled twice");
                    }
                    String originalId = orders.find("HYPERCORE", reference, tenant).orElseThrow().getTxHash();
                    require(!transfers.confirmWithdrawal(tenant, reference, originalId), "confirmed original withdrawal settled twice");
                    var originalAction = actions.find(originalId).orElseThrow();
                    require(originalAction.receipt() != null, "original real ledger execution receipt was not persisted");
                    if (phase.equals("recovery")) require(originalAction.response() == null, "lost exchange reply was fabricated during recovery");
                    int sentBeforeRecovery = submissionsSent.get();
                    require(transfers.recover(op), "confirmed funded request was not recoverable");
                    require(submissionsSent.get() == sentBeforeRecovery
                        && orders.find("HYPERCORE", reference, tenant).orElseThrow().getStatus().equals("CONFIRMED"),
                        "confirmed recovery resubmitted a payment or changed its terminal state");
                    if (phase.endsWith("owned-withdrawal")) {
                        scanner.scanAndCredit(profile);
                        BigDecimal target=balances.find(tenant,"HYPERCORE",asset,owned.get(collectorRole).getAccountId()).map(value->value.getTotalBalance()).orElse(BigDecimal.ZERO);
                        scanner.scanAndCredit(profile);
                        require(target.compareTo(balances.find(tenant,"HYPERCORE",asset,owned.get(collectorRole).getAccountId()).map(value->value.getTotalBalance()).orElse(BigDecimal.ZERO))==0,
                            "owned withdrawal receipt was credited twice");
                    }
                    requirePhysicalConservation(realApi, balances, tenant, owned.values());
                    var history = realApi.postInfo(JSON.createObjectNode().put("type", "userNonFundingLedgerUpdates").put("user", source.getAddress()).put("startTime", 0));
                    Files.writeString(root.resolve("funded-ledger-public.json"), history.toPrettyString());
                    System.out.println("HyperCore funded "+asset+" " + phase + " quorum " + pair + ": actual official execution, original ledger proof, committed signature/body, once settlement and total physical balance passed; real exchange calls=" + submissionsSent.get());
                }
            }
        }
    }
    private static boolean awaitConfirmation(java.util.function.BooleanSupplier confirmation) throws InterruptedException {
        for (int attempt=0; attempt<30; attempt++) { if (confirmation.getAsBoolean()) return true; Thread.sleep(1000); }
        return false;
    }
    private static void requirePhysicalConservation(HyperCoreApiClient api, LedgerBalanceRepository balances, UUID tenant, Collection<ChainAddressRecord> owned) {
        var physical=new HashMap<String,BigDecimal>();var principal=new HashMap<String,BigDecimal>();
        for(String asset:List.of("USDC","HYPE")){physical.put(asset,BigDecimal.ZERO);principal.put(asset,BigDecimal.ZERO);}
        for (var owner:owned) {
            var state=api.postInfo(JSON.createObjectNode().put("type","spotClearinghouseState").put("user",owner.getAddress()));
            for(var item:state.path("balances"))if(physical.containsKey(item.path("coin").asText()))physical.merge(item.path("coin").asText(),new BigDecimal(item.path("total").asText()),BigDecimal::add);
            for(String asset:principal.keySet())principal.merge(asset,balances.find(tenant,"HYPERCORE",asset,owner.getAccountId()).map(value->value.getTotalBalance()).orElse(BigDecimal.ZERO),BigDecimal::add);
        }
        for(String asset:physical.keySet())require(physical.get(asset).compareTo(principal.get(asset))==0,"funded "+asset+" physical sum differs from all booked principal");
    }
    private static void bookTestProcurement(Path root,HyperCoreApiClient api,JdbcTemplate jdbc,LedgerBalanceRepository balances,
            TransactionTemplate transaction,UUID tenant,ChainAddressRecord owner,UUID custodyId) throws Exception {
        var proof=JSON.readTree(Files.readString(root.resolve("test-hype-procurement-public.json")));var plan=proof.path("plan");
        require(plan.path("endpoint").asText().equals(ENDPOINT) && plan.path("network").asText().equals("hyperliquid-testnet")
                && plan.path("owner").asText().equals(owner.getAddress()) && plan.path("keyId").asText().equals(owner.getMpcKeyId().toString())
                && plan.path("maxMockUsdc").asText().equals("15"),"test procurement ownership or cap differs");
        var selected=JSON.readTree(Files.readString(root.resolve("test-hype-order-selected-body-public.json")));
        require(selected.path("action").equals(plan.path("action")) && selected.path("nonce").asLong()==plan.path("nonce").asLong(),"test procurement original body differs");
        var status=api.postInfo(JSON.createObjectNode().put("type","orderStatus").put("user",owner.getAddress()).put("oid",plan.path("cloid").asText()));
        require(status.equals(proof.path("orderStatus")) && status.path("status").asText().equals("order"),"original test order status differs from official record");
        var order=status.path("order").path("order");var signed=plan.path("action").path("orders").get(0);
        require(order.path("cloid").asText().equals(plan.path("cloid").asText()) && order.path("coin").asText().equals(plan.path("pair").asText())
                && order.path("side").asText().equals("B") && new BigDecimal(order.path("origSz").asText()).compareTo(new BigDecimal("0.07"))==0
                && new BigDecimal(order.path("limitPx").asText()).compareTo(new BigDecimal(signed.path("p").asText()))==0,"official test order is not the selected bounded spot buy");
        var actual=api.postInfo(JSON.createObjectNode().put("type","userFillsByTime").put("user",owner.getAddress())
                .put("startTime",Math.max(0,plan.path("nonce").asLong()-60000)).put("aggregateByTime",false));
        var expected=new HashMap<Long,JsonNode>();for(var fill:proof.path("fills"))require(expected.put(fill.path("tid").asLong(),fill)==null,"duplicate funding fill");
        BigDecimal spent=BigDecimal.ZERO,received=BigDecimal.ZERO,quantity=BigDecimal.ZERO;
        for(var fill:actual)if(fill.path("oid").asLong()==order.path("oid").asLong()) {
            require(fill.equals(expected.remove(fill.path("tid").asLong())) && fill.path("hash").asText().matches("0x[0-9a-f]{64}")
                    && fill.path("coin").asText().equals(plan.path("pair").asText()) && fill.path("side").asText().equals("B"),"official funding fill differs");
            BigDecimal amount=new BigDecimal(fill.path("sz").asText()),price=new BigDecimal(fill.path("px").asText()),fee=new BigDecimal(fill.path("fee").asText());
            require(amount.signum()>0 && price.signum()>0 && price.compareTo(new BigDecimal(signed.path("p").asText()))<=0 && fee.signum()>=0,"funding fill exceeds selected amount, price or fee scope");
            quantity=quantity.add(amount);spent=spent.add(amount.multiply(price));received=received.add(amount);
            if(fill.path("feeToken").asText().equals("USDC"))spent=spent.add(fee);
            else {require(fill.path("feeToken").asText().equals("HYPE"),"unknown funding fee token");received=received.subtract(fee);}
        }
        require(expected.isEmpty() && quantity.signum()>0 && quantity.compareTo(new BigDecimal("0.07"))<=0 && spent.signum()>0
                && spent.compareTo(new BigDecimal("15"))<=0 && received.signum()>0
                && spent.compareTo(new BigDecimal(proof.path("mockUsdcSpent").asText()))==0
                && received.compareTo(new BigDecimal(proof.path("hypeAcquired").asText()))==0,"official funding fill totals differ");
        var entries=new CustodyLedgerEntryRepository(jdbc);BigDecimal debit=spent,credit=received;
        String reference="test-hype-procurement:"+plan.path("cloid").asText();
        transaction.executeWithoutResult(tx->{
            int expense=entries.insertIfAbsent(UUID.randomUUID(),tenant,custodyId,"HYPERCORE","USDC",owner.getAccountId(),"TEST_PROCUREMENT_SPEND","DEBIT",debit,"TEST_ASSET_PROCUREMENT",reference+":USDC");
            int receipt=entries.insertIfAbsent(UUID.randomUUID(),tenant,custodyId,"HYPERCORE","HYPE",owner.getAccountId(),"TEST_PROCUREMENT_RECEIVE","CREDIT",credit,"TEST_ASSET_PROCUREMENT",reference+":HYPE");
            require(expense==receipt,"test procurement audit is partially published");
            if(expense==1){require(balances.debit("HYPERCORE","USDC",owner.getAccountId(),debit,tenant),"test procurement principal cannot cover original fills");balances.increment(tenant,"HYPERCORE","HYPE",owner.getAccountId(),credit);}
            else for(var item:Map.of("USDC",debit,"HYPE",credit).entrySet())require(jdbc.queryForObject("select amount from custody_ledger_entry where tenant_id=? and reference_type='TEST_ASSET_PROCUREMENT' and reference_id=?",BigDecimal.class,tenant,reference+":"+item.getKey()).compareTo(item.getValue())==0,"test procurement replay amount changed");
        });
    }
    private static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
