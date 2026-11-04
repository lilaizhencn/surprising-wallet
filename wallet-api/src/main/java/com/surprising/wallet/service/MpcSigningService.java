package com.surprising.wallet.service;

import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.common.signing.MoneroMpcResultVerifier;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.model.mpc.MpcKeyEpoch;
import com.surprising.wallet.model.mpc.MpcObservation;
import com.surprising.wallet.model.mpc.MpcPendingException;
import com.surprising.wallet.model.mpc.MpcSignAttempt;
import com.surprising.wallet.common.signing.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.common.signing.MoneroSigningPayload;
import com.surprising.wallet.chain.monero.MoneroSignedPayment;
import com.surprising.wallet.repository.CustodyTenantTableRepository;
import com.surprising.wallet.repository.WalletMpcKeyEpochRepository;
import com.surprising.wallet.repository.WalletMpcKeyRepository;
import com.surprising.wallet.repository.WalletMpcSignAttemptRepository;
import com.surprising.wallet.repository.WalletMpcSignRequestRepository;
import com.surprising.wallet.sdk.signing.ThresholdSignatureVerifier;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable tenant signing intent. Native calls execute only between committed transactions. */
@Service
public class MpcSigningService {
    private final CustodyTenantTableRepository tenants;
    private final WalletMpcKeyRepository keys;
    private final WalletMpcKeyEpochRepository epochs;
    private final WalletMpcSignRequestRepository requests;
    private final WalletMpcSignAttemptRepository attempts;
    private final CustodyCryptoService crypto;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final MpcAuthorizationService authorizations;

    public MpcSigningService(CustodyTenantTableRepository tenants, WalletMpcKeyRepository keys,
                             WalletMpcKeyEpochRepository epochs, WalletMpcSignRequestRepository requests,
                             WalletMpcSignAttemptRepository attempts, CustodyCryptoService crypto,
                             ObjectMapper json, PlatformTransactionManager manager,MpcAuthorizationService authorizations) {
        this.tenants = tenants;
        this.keys = keys;
        this.epochs = epochs;
        this.requests = requests;
        this.attempts = attempts;
        this.crypto = crypto;
        this.json = json;
        transaction = new TransactionTemplate(manager);
        this.authorizations=authorizations;
    }

    public MpcKey reserveKey(UUID tenant, ChainType chain, long userId, int biz, long addressIndex,
                              List<String> parties) {
        Objects.requireNonNull(tenant, "MPC tenant is required");
        Objects.requireNonNull(chain, "MPC chain is required");
        MpcClient.Algorithm algorithm = algorithm(chain);
        return inTransaction(() -> {
            var owner = tenants.findById(tenant).orElseThrow(() -> new IllegalArgumentException("MPC tenant not found"));
            require("ACTIVE".equals(owner.status()) && owner.derivationNamespace() == biz && userId >= 0 && addressIndex >= 0,
                    "MPC key coordinates do not belong to an active tenant");
            var key = keys.reserve(UUID.randomUUID(), tenant, keyGroup(chain), userId, biz, addressIndex, algorithm.name());
            require(key.algorithm().equals(algorithm.name()), "MPC key algorithm differs");
            var epoch = epochs.find(tenant, key.id(), 1).orElseGet(() -> epochs.create(new MpcClient.Request(
                    UUID.randomUUID(), tenant, key.id(), 1, MpcClient.Operation.CREATE_KEY, algorithm, parties, "")));
            require(epoch.request().online().equals(parties), "MPC key participants differ");
            return key;
        });
    }

    public MpcKey awaitKey(UUID tenant, ChainType chain, long userId, int biz, long addressIndex, MpcNodes nodes) {
        requireOutsideTransaction();
        return awaitReservedKey(tenant,reserveKey(tenant,chain,userId,biz,addressIndex,nodes.parties()),nodes);
    }

    /** One native multisig root per tenant, then one registered account per immutable custody coordinate. */
    public MpcKey awaitMoneroKey(UUID tenant,long userId,int biz,long addressIndex,String network,String genesisHash,MpcNodes nodes) {
        requireOutsideTransaction();
        require(userId>=0 && addressIndex>=0,"native Monero custody coordinates must be nonnegative");
        require(java.util.Set.of("mainnet","testnet","stagenet","regtest").contains(network)
                && genesisHash!=null && genesisHash.matches("[0-9a-f]{64}"),"native Monero network identity is required");
        var rootPolicy=Map.of("kind","ROOT","network",network,"genesisHash",genesisHash);
        var root=awaitReservedKey(tenant,reserveMoneroKey(tenant,"XMR_ROOT",0,biz,0,rootPolicy,nodes.parties(),null),nodes);
        var addressPolicy=Map.of("kind","ADDRESS","network",network,"genesisHash",genesisHash,"rootKeyId",root.id().toString());
        return awaitReservedKey(tenant,reserveMoneroKey(tenant,"XMR",userId,biz,addressIndex,addressPolicy,nodes.parties(),root.id()),nodes);
    }

    /** Read-only account facts require two original authenticated holders, including for disabled tenants. */
    public MoneroSnapshot observeMonero(UUID tenant,UUID keyId,String sender,long fromHeight,
                                       MoneroScanAnchorService.Anchor anchor,String transactionHash,MpcNodes nodes) {
        requireOutsideTransaction();
        Objects.requireNonNull(anchor,"Monero observation anchor is required");
        require(fromHeight>=0 && fromHeight<=anchor.point().height() && anchor.point().height()-fromHeight<=1000,
                "Monero observation range exceeds its bound");
        require(transactionHash==null || transactionHash.matches("[0-9a-f]{64}"),"invalid Monero observed transaction hash");
        var key=requireKey(tenant,keyId);
        require(key.state()==MpcKey.State.ACTIVE && key.activeEpoch()==1 && key.keyGroup().equals("XMR")
                && key.algorithm().equals(MpcClient.Algorithm.MONERO_NATIVE_2_OF_3.name()),"Monero observation requires an active registered native address");
        var epoch=epochs.find(tenant,keyId,key.activeEpoch()).orElseThrow();
        var creation=json.readTree(HexFormat.of().parseHex(epoch.request().messageHex()));
        require(epoch.state()==MpcKeyEpoch.State.ACTIVE && epoch.request().algorithm()==MpcClient.Algorithm.MONERO_NATIVE_2_OF_3
                && creation.path("kind").asText().equals("ADDRESS")
                && creation.path("network").asText().equals(anchor.network())
                && creation.path("genesisHash").asText().equals(anchor.genesisHash())
                && epoch.request().online().containsAll(nodes.online()),"Monero observation network or original participants differ");
        String expected=com.surprising.wallet.sdk.signing.MoneroPublicAddressCodec.encode(
                HexFormat.of().parseHex(key.publicKeyHex()),anchor.network(),true);
        require(expected.equals(sender),"Monero observation sender differs from the registered public key");
        var policy=new java.util.TreeMap<String,Object>();
        policy.put("kind","SNAPSHOT");policy.put("network",anchor.network());policy.put("genesisHash",anchor.genesisHash());
        policy.put("sender",sender);policy.put("fromHeight",fromHeight);policy.put("height",anchor.point().height());
        policy.put("blockHash",anchor.point().hash());if(transactionHash!=null)policy.put("transactionHash",transactionHash);
        var request=new MpcClient.Request(UUID.randomUUID(),tenant,keyId,key.activeEpoch(),MpcClient.Operation.MONERO_OBSERVE,
                MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,nodes.online(),HexFormat.of().formatHex(json.writeValueAsBytes(policy)));
        long deadline=System.nanoTime()+nodes.operationTimeout().toNanos();
        while(System.nanoTime()<deadline) {
            var exchange=exchange(request,nodes.clients());
            if(MoneroMpcResultVerifier.complete(request,exchange.results())) {
                var result=exchange.results().get(request.online().getFirst());
                require(key.publicKeyHex().equals(result.publicKeyHex()) && sender.equals(result.publicAddress()),
                        "Monero account observation changed the registered public identity");
                return new MoneroSnapshot(request,result);
            }
            if(exchange.results().values().stream().anyMatch(result->result.state()==MpcClient.State.FAILED
                    || result.state()==MpcClient.State.INTERRUPTED || result.state()==MpcClient.State.INDETERMINATE))break;
            pause(request.sessionId(),"OBSERVATION_PENDING");
        }
        throw new MpcPendingException(request.sessionId(),"OBSERVATION_PENDING");
    }

    /** Public request and matching two-holder result can be retained with the caller's deposit audit. */
    public record MoneroSnapshot(MpcClient.Request request,MpcClient.Result result) { }

    /** Once the native signing stage is durably entered, recovery cannot prepare more funds or another input domain. */
    public boolean hasStartedMoneroSignature(UUID tenant,UUID requestId) {
        var request=requests.find(tenant,requestId).orElseThrow();
        require(request.command().chain()==ChainType.XMR && MoneroSigningPayload.ENCODING.equals(request.command().payloadEncoding()),"native Monero payment required");
        var attempt=attempts.find(tenant,request.currentAttemptId()).orElseThrow();
        require(attempt.requestId().equals(requestId),"Monero original attempt belongs to another request");
        return attempt.state()!=MpcSignAttempt.State.PENDING;
    }

    /** Recover the exact public identity from the durable, two-holder authenticated encrypted signature. */
    public MoneroSignedPayment selectedMonero(UUID tenant,UUID requestId) {
        var request=requests.find(tenant,requestId).orElseThrow();
        require(request.command().chain()==ChainType.XMR && request.state()==MpcSignRequest.State.COMPLETED
                && MoneroSigningPayload.ENCODING.equals(request.command().payloadEncoding()),"Monero payment intent is not completed");
        var payload=json.readValue(HexFormat.of().parseHex(request.command().payloadHex()),MoneroSigningPayload.class);
        require(payload.preimageHex(json).equals(request.command().preimageHex()),"Monero immutable payment policy differs");
        var attempt=attempts.find(tenant,request.currentAttemptId()).orElseThrow();
        require(attempt.requestId().equals(requestId),"Monero selected attempt belongs to another business intent");
        var key=requireKey(tenant,request.command().keyId());
        return MoneroSignedPayment.verify(attempt.request(),key.publicKeyHex(),signature(tenant,requestId).orElseThrow());
    }

    /** Relay only the original completed transaction, using one surviving original material holder. */
    public MoneroRelay relayMonero(UUID tenant,UUID requestId,String expectedHash,MpcNodes nodes,
                                  HotWalletBroadcastFence broadcastFence) {
        requireOutsideTransaction();var selected=selectedMonero(tenant,requestId);
        require(selected.transactionHash().equals(expectedHash),"Monero relay differs from the selected original transaction");
        var request=requests.find(tenant,requestId).orElseThrow();
        var payload=json.readValue(HexFormat.of().parseHex(request.command().payloadHex()),MoneroSigningPayload.class);
        var key=requireKey(tenant,request.command().keyId());var relays=new java.util.ArrayList<MpcClient.Request>();
        for(String party:selected.originalHolders()) {
            require(nodes.parties().contains(party),"Monero original relay holder is no longer configured");
            var policy=new java.util.TreeMap<String,Object>();policy.put("kind","RELAY");policy.put("network",payload.network());
            policy.put("genesisHash",payload.genesisHash());policy.put("sender",payload.sender());
            policy.put("signSessionId",selected.signSessionId().toString());policy.put("signIntentHash",selected.signIntentHash());
            policy.put("transactionHash",selected.transactionHash());
            UUID relaySession=UUID.nameUUIDFromBytes(("SW-MONERO-RELAY-V1:"+tenant+":"+requestId+":"+selected.signSessionId()+":"+party).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            relays.add(new MpcClient.Request(relaySession,tenant,key.id(),request.command().keyEpoch(),MpcClient.Operation.MONERO_RELAY,
                    MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,List.of(party),HexFormat.of().formatHex(json.writeValueAsBytes(policy))));
        }
        long deadline=System.nanoTime()+nodes.operationTimeout().toNanos();
        do {
            for(var relay:relays) {
                var proof=authorizations.authorizeMoneroRelay(requestId,selected.signSessionId(),relay);
                if(proof==null)continue; // Only try another original material holder; never construct another payment.
                var exchange=broadcastFence.withExternalLegacyBroadcastPermit(tenant,"XMR",
                        ()->exchange(relay,nodes.clients(),proof));
                var result=exchange.results().get(relay.online().getFirst());
                if(result!=null && result.state()==MpcClient.State.COMPLETED) {
                    require(key.publicKeyHex().equals(result.publicKeyHex()) && payload.sender().equals(result.publicAddress()),
                            "Monero relay holder changed its registered public identity");
                    return new MoneroRelay(relay,result);
                }
            }
            pause(requestId,"RELAY_UNKNOWN");
        } while(System.nanoTime()<deadline);
        throw new MpcPendingException(requestId,"RELAY_UNKNOWN");
    }

    public record MoneroRelay(MpcClient.Request request,MpcClient.Result result) { }

    private MpcKey reserveMoneroKey(UUID tenant,String group,long userId,int biz,long index,Map<String,String> policy,List<String> parties,UUID rootId) {
        String encoded=HexFormat.of().formatHex(json.writeValueAsBytes(new java.util.TreeMap<>(policy)));
        return inTransaction(()->{
            var owner=tenants.findById(tenant).orElseThrow();
            require(owner.status().equals("ACTIVE") && owner.derivationNamespace()==biz && userId>=0 && index>=0,"native Monero coordinates do not belong to an active tenant");
            if(rootId!=null) {
                var parent=keys.lock(tenant,rootId).orElseThrow();
                require(parent.state()==MpcKey.State.ACTIVE && parent.keyGroup().equals("XMR_ROOT")
                        && parent.algorithm().equals(MpcClient.Algorithm.MONERO_NATIVE_2_OF_3.name()) && parent.biz()==biz,
                        "native Monero root is not active for this tenant");
            }
            var key=keys.reserve(UUID.randomUUID(),tenant,group,userId,biz,index,MpcClient.Algorithm.MONERO_NATIVE_2_OF_3.name());
            require(key.algorithm().equals(MpcClient.Algorithm.MONERO_NATIVE_2_OF_3.name()),"native Monero key protocol differs");
            var epoch=epochs.find(tenant,key.id(),1).orElseGet(()->epochs.create(new MpcClient.Request(UUID.randomUUID(),tenant,key.id(),1,
                    MpcClient.Operation.CREATE_KEY,MpcClient.Algorithm.MONERO_NATIVE_2_OF_3,parties,encoded)));
            require(epoch.request().online().equals(parties) && epoch.request().messageHex().equals(encoded),"native Monero root, network or participants changed");
            return key;
        });
    }

    private MpcKey awaitReservedKey(UUID tenant,MpcKey key,MpcNodes nodes) {
        long deadline = System.nanoTime() + nodes.operationTimeout().toNanos();
        while (key.state() == MpcKey.State.CREATING && System.nanoTime() < deadline) {
            key = advanceKey(tenant, key.id(), nodes.clients());
            if (key.state() == MpcKey.State.CREATING) pause(key.id(), key.state().name());
        }
        if (key.state() != MpcKey.State.ACTIVE) throw new MpcPendingException(key.id(), key.state().name());
        return authorizations.registerKey(tenant,key.id());
    }

    /** Locks the public key while its chain address or signing request is committed. */
    public MpcKey lockActiveKey(UUID tenant, UUID id, ChainType chain) {
        require(TransactionSynchronizationManager.isActualTransactionActive(), "MPC key publication requires a transaction");
        var key = keys.lock(tenant, id).orElseThrow(() -> new IllegalArgumentException("MPC key is unavailable"));
        require(key.state() == MpcKey.State.ACTIVE && key.keyGroup().equals(keyGroup(chain)), "MPC key is not active for this chain");
        require(epochs.registration(tenant,id,key.activeEpoch()).isPresent(),"MPC key is not independently registered for publication");
        return key;
    }

    /** No address may be exposed until all three parties durably agree on its public key. */
    public MpcKey advanceKey(UUID tenant, UUID keyId, List<MpcClient> clients) {
        requireOutsideTransaction();
        var snapshot = inTransaction(() -> {
            var key = requireKey(tenant, keyId);
            return new KeySnapshot(key, epochs.find(tenant, keyId, 1).orElseThrow());
        });
        if (snapshot.key().state() != MpcKey.State.CREATING)
            return snapshot.key().state()==MpcKey.State.ACTIVE?authorizations.registerKey(tenant,keyId):snapshot.key();
        var exchange = exchange(snapshot.epoch().request(), clients);
        var completed = exchange.results().values().stream().filter(result -> result.state() == MpcClient.State.COMPLETED).toList();
        boolean ready = completed.size() == 3 && completed.stream().map(MpcClient.Result::publicKeyHex).distinct().count() == 1
                && (snapshot.epoch().request().algorithm()!=MpcClient.Algorithm.MONERO_NATIVE_2_OF_3
                    || completed.stream().map(MpcClient.Result::publicAddress).distinct().count()==1);
        boolean failed = exchange.results().values().stream().anyMatch(result ->
                result.state() == MpcClient.State.FAILED || result.state() == MpcClient.State.INTERRUPTED)
                || (completed.size() == 3 && !ready);
        var activated=inTransaction(() -> {
            var key = keys.lock(tenant, keyId).orElseThrow();
            if (key.state() != MpcKey.State.CREATING) return key;
            var state = ready ? MpcKeyEpoch.State.ACTIVE : failed ? MpcKeyEpoch.State.FAILED : MpcKeyEpoch.State.PREPARING;
            require(epochs.observations(tenant, keyId, 1, state, exchange.observations()) == 1,
                    "MPC key ceremony state changed");
            if (ready) require(keys.activate(tenant, keyId, 1, completed.getFirst().publicKeyHex()) == 1, "MPC key activation raced");
            else if (failed) require(keys.failCreation(tenant, keyId) == 1, "MPC key failure raced");
            return requireKey(tenant, keyId);
        });
        return activated.state()==MpcKey.State.ACTIVE?authorizations.registerKey(tenant,keyId):activated;
    }

    /** The source epoch makes a repeated operator call resume the same ceremony, even after activation. */
    public MpcKey reserveRefresh(UUID tenant, UUID keyId, int sourceEpoch, List<String> parties,
                                 String actor, String reason) {
        require(sourceEpoch > 0 && sourceEpoch < 1_000_000, "MPC refresh source epoch is outside limits");
        require(actor != null && !actor.isBlank() && actor.length() <= 192
                && reason != null && reason.trim().length() >= 5 && reason.length() <= 512,
                "MPC refresh requires actor and reason");
        return inTransaction(() -> {
            var key = keys.lock(tenant, keyId).orElseThrow(() -> new IllegalArgumentException("MPC key is unavailable"));
            require(!key.algorithm().equals(MpcClient.Algorithm.MONERO_NATIVE_2_OF_3.name()),
                    "Monero key maintenance requires its native protocol");
            var existing = epochs.find(tenant, keyId, sourceEpoch + 1);
            if (existing.isPresent()) {
                var epoch = existing.orElseThrow();
                require(epoch.request().operation() == MpcClient.Operation.REFRESH
                        && epoch.request().online().equals(parties) && actor.trim().equals(epoch.refreshActor())
                        && reason.trim().equals(epoch.refreshReason()), "MPC original refresh identity or audit differs");
                return key;
            }
            var owner = tenants.findById(tenant).orElseThrow();
            require("ACTIVE".equals(owner.status()) && key.state() == MpcKey.State.ACTIVE
                    && key.activeEpoch() == sourceEpoch, "MPC refresh requires the current active tenant key");
            var source = epochs.find(tenant, keyId, sourceEpoch).orElseThrow();
            require(source.state() == MpcKeyEpoch.State.ACTIVE && source.request().online().equals(parties),
                    "MPC refresh must retain all three original holders and their order");
            require(epochs.registration(tenant,keyId,sourceEpoch).isPresent(),"MPC refresh source has no independent registration receipt");
            require(!requests.hasPendingForKey(tenant, keyId), "MPC key has unresolved signing requests");
            epochs.create(new MpcClient.Request(UUID.randomUUID(), tenant, keyId, sourceEpoch + 1,
                    MpcClient.Operation.REFRESH, MpcClient.Algorithm.valueOf(key.algorithm()), parties, ""), actor.trim(), reason.trim());
            require(keys.beginRefresh(tenant, keyId, sourceEpoch) == 1, "MPC refresh reservation raced");
            return requireKey(tenant, keyId);
        });
    }

    /** No new signing is admitted until all holders durably attest the unchanged joint public key. */
    public MpcKey advanceRefresh(UUID tenant, UUID keyId, int targetEpoch, List<MpcClient> clients) {
        requireOutsideTransaction();
        require(targetEpoch > 1 && targetEpoch <= 1_000_000, "MPC refresh target epoch is outside limits");
        var snapshot = inTransaction(() -> {
            var key = keys.lock(tenant, keyId).orElseThrow();
            var epoch = epochs.find(tenant, keyId, targetEpoch).orElseThrow();
            require(epoch.request().operation() == MpcClient.Operation.REFRESH, "MPC epoch is not a refresh");
            if (epoch.state() == MpcKeyEpoch.State.ACTIVE || epoch.state() == MpcKeyEpoch.State.RETIRED) {
                require(key.activeEpoch() >= targetEpoch, "MPC active version differs from its completed refresh");
            } else require(key.state() == MpcKey.State.REFRESHING && key.activeEpoch() == targetEpoch - 1
                    && epoch.state() == MpcKeyEpoch.State.PREPARING, "MPC original refresh is unavailable");
            return new KeySnapshot(key, epoch);
        });
        if (snapshot.epoch().state() != MpcKeyEpoch.State.PREPARING) return authorizations.registerKey(tenant,keyId);
        var exchange = exchange(snapshot.epoch().request(), clients);
        boolean ready = exchange.results().size() == 3 && exchange.results().values().stream().allMatch(result ->
                result.state() == MpcClient.State.COMPLETED && snapshot.key().publicKeyHex().equals(result.publicKeyHex()));
        var refreshed=inTransaction(() -> {
            var key = keys.lock(tenant, keyId).orElseThrow();
            var epoch = epochs.find(tenant, keyId, targetEpoch).orElseThrow();
            if (epoch.state() == MpcKeyEpoch.State.ACTIVE || epoch.state() == MpcKeyEpoch.State.RETIRED) return key;
            require(key.state() == MpcKey.State.REFRESHING && key.activeEpoch() == targetEpoch - 1
                    && epoch.request().equals(snapshot.epoch().request()), "MPC refresh activation raced");
            require(epochs.observations(tenant, keyId, targetEpoch,
                    ready ? MpcKeyEpoch.State.ACTIVE : MpcKeyEpoch.State.PREPARING, exchange.observations()) == 1,
                    "MPC refresh observations raced");
            if (ready) {
                require(epochs.retire(tenant, keyId, targetEpoch - 1) == 1, "MPC original epoch retirement raced");
                require(keys.finishRefresh(tenant, keyId, targetEpoch - 1, snapshot.key().publicKeyHex()) == 1,
                        "MPC refreshed epoch activation raced");
            }
            return requireKey(tenant, keyId);
        });
        return refreshed.state()==MpcKey.State.ACTIVE?authorizations.registerKey(tenant,keyId):refreshed;
    }

    /** Joins the caller's balance/nonce/UTXO transaction; no native call occurs here. */
    public MpcSignRequest reserveSignature(MpcSignCommand command, List<String> online) {
        require(TransactionSynchronizationManager.isActualTransactionActive(),
                "MPC signing reservation requires the caller transaction");
        return inTransaction(() -> {
            var key = keys.lock(command.tenantId(), command.keyId()).orElseThrow(() -> new IllegalArgumentException("MPC key is unavailable"));
            require(key.state() == MpcKey.State.ACTIVE && key.activeEpoch() == command.keyEpoch()
                            && key.keyGroup().equals(keyGroup(command.chain())), "MPC key is not active for this chain and epoch");
            var epoch = epochs.find(command.tenantId(), command.keyId(), command.keyEpoch()).orElseThrow();
            require(epoch.state() == MpcKeyEpoch.State.ACTIVE && epoch.request().online().containsAll(online),
                    "MPC signing quorum is not registered");
            require(epochs.registration(command.tenantId(),key.id(),command.keyEpoch()).isPresent(),"MPC signing epoch has no independent registry receipt");
            var request = requests.reserve(UUID.randomUUID(), command,command.businessReferences(json));
            require(request.command().equals(command), "MPC business reference already has a different transaction intent");
            require(request.state()!=MpcSignRequest.State.CANCELLED,"MPC signing business was independently cancelled");
            if (request.currentAttemptId() == null) {
                var nativeRequest = new MpcClient.Request(UUID.randomUUID(), command.tenantId(), command.keyId(), command.keyEpoch(),
                        MpcClient.Operation.SIGN, MpcClient.Algorithm.valueOf(key.algorithm()), online, command.preimageHex());
                attempts.create(request.id(), nativeRequest, null, null);
                require(requests.pointToAttempt(command.tenantId(), request.id(), nativeRequest.sessionId()) == 1,
                        "MPC signing attempt could not be bound");
            }
            return requests.find(command.tenantId(), request.id()).orElseThrow();
        });
    }

    public MpcSignRequest advanceSignature(UUID tenant, UUID requestId, List<MpcClient> clients) {
        requireOutsideTransaction();
        var request = inTransaction(() -> requests.find(tenant, requestId).orElseThrow());
        if (request.state() == MpcSignRequest.State.COMPLETED) return request;
        if (request.state() == MpcSignRequest.State.CANCELLED) return request;
        require(request.currentAttemptId() != null, "MPC request has no current attempt");
        return advanceAttempt(tenant, requestId, request.currentAttemptId(), clients);
    }

    public byte[] awaitSignature(UUID tenant, UUID requestId, MpcNodes nodes) {
        requireOutsideTransaction();
        long deadline = System.nanoTime() + nodes.operationTimeout().toNanos();
        MpcSignRequest request;
        do {
            request = advanceSignature(tenant, requestId, nodes.clients());
            if (request.state() == MpcSignRequest.State.COMPLETED) return signature(tenant, requestId).orElseThrow();
            if (request.state() == MpcSignRequest.State.INDETERMINATE || request.state()==MpcSignRequest.State.CANCELLED) break;
            pause(requestId, request.state().name());
        } while (System.nanoTime() < deadline);
        throw new MpcPendingException(requestId, request.state().name());
    }

    /** Reconciliation can retain a late result even after another attempt has already completed. */
    public MpcSignRequest advanceAttempt(UUID tenant, UUID requestId, UUID attemptId, List<MpcClient> clients) {
        requireOutsideTransaction();
        var snapshot = inTransaction(() -> {
            var request = requests.find(tenant, requestId).orElseThrow();
            var attempt = attempts.find(tenant, attemptId).orElseThrow();
            require(attempt.requestId().equals(requestId), "MPC attempt belongs to another request");
            return new SigningSnapshot(request, attempt, requireKey(tenant, request.command().keyId()));
        });
        if (snapshot.request().state()==MpcSignRequest.State.CANCELLED || snapshot.attempt().state() == MpcSignAttempt.State.COMPLETED) return snapshot.request();
        var proof=authorizations.authorize(tenant,requestId,attemptId); // Returns only after the original proof commits.
        inTransaction(()->attempts.start(tenant,attemptId)); // Durable dispatch fence before any native SIGN call.
        var exchange = exchange(snapshot.attempt().request(), clients,proof);
        String receiver = snapshot.attempt().request().online().getFirst();
        var signed = exchange.results().get(receiver);
        boolean nativeMonero = snapshot.attempt().request().algorithm() == MpcClient.Algorithm.MONERO_NATIVE_2_OF_3;
        boolean complete = nativeMonero ? MoneroMpcResultVerifier.complete(snapshot.attempt().request(), exchange.results())
                : signed != null && signed.state() == MpcClient.State.COMPLETED;
        if (complete) require(snapshot.key().publicKeyHex().equals(signed.publicKeyHex()), "MPC signature public key differs from tenant key");
        boolean unknown = exchange.results().values().stream().anyMatch(result ->
                result.state() == MpcClient.State.FAILED || result.state() == MpcClient.State.INDETERMINATE
                        || result.state() == MpcClient.State.INTERRUPTED)
                || exchange.observations().values().stream().anyMatch(observation ->
                    Set.of("UNREACHABLE", "INTERRUPTED", "INDETERMINATE").contains(observation.state()));
        return inTransaction(() -> {
            keys.lock(tenant, snapshot.key().id()).orElseThrow();
            var current = requests.lock(tenant, requestId).orElseThrow();
            var attempt = attempts.find(tenant, attemptId).orElseThrow();
            boolean indeterminate = unknown || attempt.state() == MpcSignAttempt.State.INDETERMINATE;
            if (attempt.state() != MpcSignAttempt.State.COMPLETED) {
                if (complete) {
                    var bound = new BoundSignature(tenant, requestId, attemptId, snapshot.key().id(),
                            current.command().keyEpoch(), snapshot.key().publicKeyHex(), current.command().preimageHex(), signed.signatureHex(),
                            nativeMonero ? snapshot.attempt().request().online() : List.of(receiver));
                    require(attempts.complete(tenant, attemptId, crypto.encrypt(json.writeValueAsString(bound)), exchange.observations()) == 1,
                            "MPC signed fact could not be persisted");
                } else attempts.observe(tenant, attemptId, indeterminate ? MpcSignAttempt.State.INDETERMINATE : MpcSignAttempt.State.RUNNING,
                        exchange.observations());
            }
            if (current.state() != MpcSignRequest.State.COMPLETED) {
                if (complete) {
                    require(requests.pointToAttempt(tenant, requestId, attemptId) == 1, "MPC result selection raced");
                    require(requests.recordState(tenant, requestId, attemptId, MpcSignRequest.State.COMPLETED) == 1, "MPC completion raced");
                } else requests.recordState(tenant, requestId, attemptId,
                        indeterminate ? MpcSignRequest.State.INDETERMINATE : MpcSignRequest.State.SIGNING);
            }
            return requests.find(tenant, requestId).orElseThrow();
        });
    }

    public Optional<byte[]> signature(UUID tenant, UUID requestId) {
        return inTransaction(() -> {
            var request = requests.find(tenant, requestId).orElseThrow();
            if (request.state() != MpcSignRequest.State.COMPLETED) return Optional.empty();
            var attempt = attempts.find(tenant, request.currentAttemptId()).orElseThrow();
            require(attempt.state() == MpcSignAttempt.State.COMPLETED, "MPC completed request has no signed fact");
            var key = requireKey(tenant, request.command().keyId());
            var bound = json.readValue(crypto.decrypt(attempt.signatureCiphertext()), BoundSignature.class);
            require(bound.tenantId().equals(tenant) && bound.requestId().equals(requestId)
                            && bound.attemptId().equals(request.currentAttemptId()) && bound.keyId().equals(key.id())
                            && bound.epoch() == request.command().keyEpoch() && bound.publicKeyHex().equals(key.publicKeyHex())
                            && bound.preimageHex().equals(request.command().preimageHex()), "MPC encrypted signature ownership differs");
            byte[] publicKey = HexFormat.of().parseHex(key.publicKeyHex());
            byte[] preimage = HexFormat.of().parseHex(bound.preimageHex());
            byte[] signature = HexFormat.of().parseHex(bound.signatureHex());
            var authenticated = attempt.request().algorithm() == MpcClient.Algorithm.MONERO_NATIVE_2_OF_3
                    ? attempt.request().online() : List.of(attempt.request().online().getFirst());
            require(authenticated.equals(bound.authenticatedParties()), "MPC persisted result attestation differs");
            require(switch (MpcClient.Algorithm.valueOf(key.algorithm())) {
                case ECDSA_SECP256K1 -> ThresholdSignatureVerifier.ecdsa(publicKey, preimage, signature);
                case ED25519 -> ThresholdSignatureVerifier.ed25519(publicKey, preimage, signature);
                case SCHNORR_BIP340 -> ThresholdSignatureVerifier.bip340(publicKey, preimage, signature);
                case MONERO_NATIVE_2_OF_3 -> {
                    MoneroMpcResultVerifier.artifact(attempt.request(), key.publicKeyHex(), bound.signatureHex());
                    yield true;
                }
            }, "MPC persisted signature failed verification");
            return Optional.of(signature);
        });
    }

    public MpcSignRequest replay(UUID tenant, UUID requestId, List<String> online, String actor, String reason) {
        require(actor != null && !actor.isBlank() && actor.length() <= 192
                        && actor.equals(actor.trim()) && actor.chars().noneMatch(Character::isISOControl)
                        && reason != null && reason.trim().length() >= 5 && reason.length() <= 512 && reason.chars().noneMatch(Character::isISOControl),
                "MPC replay requires actor and reason");
        return inTransaction(() -> {
            var initial = requests.find(tenant, requestId).orElseThrow();
            var key = keys.lock(tenant, initial.command().keyId()).orElseThrow();
            var request = requests.lock(tenant, requestId).orElseThrow();
            require(request.state() == MpcSignRequest.State.INDETERMINATE, "MPC request is not eligible for replay");
            var previous = attempts.find(tenant, request.currentAttemptId()).orElseThrow();
            require(previous.state() == MpcSignAttempt.State.INDETERMINATE, "MPC current attempt is still processing");
            require(!key.algorithm().equals(MpcClient.Algorithm.MONERO_NATIVE_2_OF_3.name()),
                    "Monero recovery must retain the original signing session and selected inputs");
            var epoch = epochs.find(tenant, key.id(), request.command().keyEpoch()).orElseThrow();
            require(key.state() == MpcKey.State.ACTIVE && key.activeEpoch() == request.command().keyEpoch()
                            && epoch.state() == MpcKeyEpoch.State.ACTIVE && epoch.request().online().containsAll(online),
                    "MPC replay epoch or quorum is unavailable");
            var next = new MpcClient.Request(UUID.randomUUID(), tenant, key.id(), request.command().keyEpoch(),
                    MpcClient.Operation.SIGN, MpcClient.Algorithm.valueOf(key.algorithm()), online, request.command().preimageHex());
            attempts.create(requestId, next, actor, reason.trim());
            require(requests.pointToAttempt(tenant, requestId, next.sessionId()) == 1, "MPC replay binding raced");
            return requests.find(tenant, requestId).orElseThrow();
        });
    }

    private static Exchange exchange(MpcClient.Request request, List<MpcClient> clients) {
        require(request.operation()!=MpcClient.Operation.SIGN && request.operation()!=MpcClient.Operation.MONERO_RELAY,
                "native payment execution requires an independently persisted authorization proof");
        return exchange(request,clients,null);
    }

    private static Exchange exchange(MpcClient.Request request,List<MpcClient> clients,com.surprising.wallet.common.approval.SigningAuthorization.Proof proof) {
        boolean payment=request.operation()==MpcClient.Operation.SIGN || request.operation()==MpcClient.Operation.MONERO_RELAY;
        require(payment==(proof!=null),"native payment authorization is missing or has the wrong operation scope");
        var byId = new LinkedHashMap<String, MpcClient>();
        for (var client : clients) require(byId.put(client.node().partyId(), client) == null, "duplicate MPC client identity");
        require(byId.keySet().containsAll(request.online()), "MPC clients do not cover the registered quorum");
        var results = new LinkedHashMap<String, MpcClient.Result>();
        var observations = new LinkedHashMap<String, MpcObservation>();
        var ordered = request.online().stream().sorted().toList();
        // Observe every node first: a completed result remains recoverable even if a lower node is offline.
        for (String party : ordered) {
            try {
                var client = byId.get(party);
                var result = client.result(request);
                results.put(party, result);
                observations.put(party, new MpcObservation(result.state().name(), result.intentHash()));
            } catch (MpcClient.CallException unknown) {
                observations.put(party, new MpcObservation("UNREACHABLE", null));
            }
        }
        // Native processes execute one protocol each. Acquire them in one global order;
        // a busy/unknown lower node must not make this session occupy higher nodes.
        for (String party : ordered) {
            var result = results.get(party);
            if (result == null) break;
            boolean originalSigningRecovery = request.operation() == MpcClient.Operation.SIGN
                    && (result.state()==MpcClient.State.INTERRUPTED || (request.algorithm()==MpcClient.Algorithm.MONERO_NATIVE_2_OF_3
                    && result.state()==MpcClient.State.INDETERMINATE));
            boolean originalRefreshRecovery = request.operation() == MpcClient.Operation.REFRESH
                    && Set.of(MpcClient.State.INTERRUPTED, MpcClient.State.FAILED).contains(result.state());
            if (result.state() == MpcClient.State.ABSENT || originalSigningRecovery || originalRefreshRecovery) {
                try {
                    result = payment?byId.get(party).submit(request,proof):byId.get(party).submit(request);
                    results.put(party, result);
                    if (!originalSigningRecovery || result.state() == MpcClient.State.COMPLETED)
                        observations.put(party, new MpcObservation(result.state().name(), result.intentHash()));
                } catch (MpcClient.CallException unknown) {
                    observations.put(party, new MpcObservation("UNREACHABLE", null));
                    break;
                }
            }
            if (result.state() != MpcClient.State.RUNNING && result.state() != MpcClient.State.COMPLETED) break;
        }
        return new Exchange(results, observations);
    }

    public static String keyGroup(ChainType chain) { return chain.isEvm() ? "EVM" : chain.name(); }

    public static MpcClient.Algorithm algorithm(ChainType chain) {
        if (chain.isEvm()) return MpcClient.Algorithm.ECDSA_SECP256K1;
        return switch (chain) {
            case BTC, LTC, DOGE, BCH, TRON, XRP, HYPERCORE -> MpcClient.Algorithm.ECDSA_SECP256K1;
            case SOLANA, TON, APTOS, SUI, ADA, DOT, NEAR -> MpcClient.Algorithm.ED25519;
            case XMR -> throw new IllegalArgumentException("XMR requires its native multisig key ceremony");
            default -> throw new IllegalArgumentException("unsupported MPC chain");
        };
    }

    public MpcKey requireKey(UUID tenant, UUID id) {
        return keys.find(tenant, id).orElseThrow(() -> new IllegalArgumentException("MPC key is unavailable"));
    }

    private <T> T inTransaction(Supplier<T> work) { return transaction.execute(status -> work.get()); }
    private static void requireOutsideTransaction() {
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "MPC network calls require a committed caller transaction");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
    private static void pause(UUID reference, String state) {
        try { Thread.sleep(50); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new MpcPendingException(reference, state);
        }
    }
    private record KeySnapshot(MpcKey key, MpcKeyEpoch epoch) { }
    private record SigningSnapshot(MpcSignRequest request, MpcSignAttempt attempt, MpcKey key) { }
    private record Exchange(Map<String, MpcClient.Result> results, Map<String, MpcObservation> observations) { }
    private record BoundSignature(UUID tenantId, UUID requestId, UUID attemptId, UUID keyId, int epoch,
                                  String publicKeyHex, String preimageHex, String signatureHex, List<String> authenticatedParties) { }
}
