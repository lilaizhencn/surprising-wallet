package com.surprising.wallet.service;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.json.JacksonJson;
import com.surprising.wallet.common.queue.PgmqClient;
import com.surprising.wallet.common.queue.QueueTenant;
import com.surprising.wallet.common.queue.QueueWorker;
import com.surprising.wallet.common.queue.WalletQueue;
import com.surprising.wallet.common.signing.UtxoSignedTransaction;
import com.surprising.wallet.common.signing.UtxoSigningRequest;
import com.surprising.wallet.common.signing.UtxoSigningResult;
import com.surprising.wallet.common.signing.UtxoSigningTask;
import com.surprising.wallet.common.utils.Constants;
import com.surprising.wallet.model.mpc.MpcKey;
import com.surprising.wallet.model.mpc.MpcPendingException;
import com.surprising.wallet.common.signing.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.common.signing.UtxoSigningPayload;
import com.surprising.wallet.repository.ChainAddressRepository;
import com.surprising.wallet.repository.ChainProfileRepository;
import com.surprising.wallet.repository.ChainSigningTransactionRepository;
import com.surprising.wallet.repository.CollectionRecordRepository;
import com.surprising.wallet.repository.UtxoRepository;
import com.surprising.wallet.repository.WalletMpcSignRequestRepository;
import com.surprising.wallet.repository.WithdrawalOrderRepository;
import com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Public-only UTXO planning and durable remote signing. No chain RPC or private keys. */
@Service
public class UtxoMpcSigningService {
    private static final HexFormat HEX = HexFormat.of();
    private static final String ENCODING = "UTXO_MPC_JSON_V1";
    private final ChainSigningTransactionRepository transactions;
    private final ChainAddressRepository addresses;
    private final ChainProfileRepository profiles;
    private final UtxoRepository utxos;
    private final WithdrawalOrderRepository orders;
    private final CollectionRecordRepository collections;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final PgmqClient queue;
    private final QueueWorker worker;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;

    public UtxoMpcSigningService(ChainSigningTransactionRepository transactions, ChainAddressRepository addresses,
            ChainProfileRepository profiles, UtxoRepository utxos, WithdrawalOrderRepository orders,
            CollectionRecordRepository collections, WalletMpcSignRequestRepository requests,
            MpcSigningService signing, MpcNodes nodes, PgmqClient queue, QueueWorker worker,
            ObjectMapper json, PlatformTransactionManager manager) {
        this.transactions = transactions;
        this.addresses = addresses;
        this.profiles = profiles;
        this.utxos = utxos;
        this.orders = orders;
        this.collections = collections;
        this.requests = requests;
        this.signing = signing;
        this.nodes = nodes;
        this.queue = queue;
        this.worker = worker;
        this.json = json;
        transaction = new TransactionTemplate(manager);
    }

    /** Joins the initial order/funds/UTXO-lock/enqueue transaction; performs no native calls. */
    public void reserve(UtxoSigningTask task) {
        requireTransaction();
        var request = task.request();
        request.validateForSigning();
        var parent = transactions.lockById(request.asset().chain(), task.transactionId()).orElseThrow();
        require(parent.request().equals(request) && parent.status() == Constants.SIGNING, "UTXO signing parent changed");
        validateBusiness(request);
        for (var input : request.inputs()) {
            var locked = utxos.lockInput(request.asset().chain(), input.txId(), input.vout(), Integer.toString(task.transactionId()))
                    .orElseThrow(() -> new IllegalStateException("MPC UTXO input lock missing"));
            require(locked.address().equals(input.address()) && locked.amount().compareTo(input.amount()) == 0,
                    "MPC UTXO input differs from the locked chain observation");
        }
        var owned = ownedKeys(request);
        var keys = owned.inputs();
        var unsigned = prepare(request, keys);
        var payload = new UtxoSigningPayload(task, unsigned, keys.stream().map(MpcKey::id).toList(),owned.change().id());
        String payloadHex = HEX.formatHex(json.writeValueAsBytes(payload));
        for (int i = 0; i < keys.size(); i++) signing.reserveSignature(command(payload, payloadHex, keys.get(i), i), nodes.online());
    }

    /** Actual scripts, compact-size counts and a maximum-size single joint-key signature per input. */
    public long estimateFee(UtxoSigningRequest request, boolean includeChange, long rate) {
        requireTransaction();
        var outputs = new ArrayList<>(request.payments().stream().map(UtxoSigningRequest.Payment::address).toList());
        if (includeChange) outputs.add(request.changeAddress());
        long size = MpcUtxoTransactionCodec.estimateVBytes(request.asset().chain(), request.asset().network(),
                publicInputs(request, ownedKeys(request).inputs()), outputs);
        return MpcUtxoTransactionCodec.feeAtomic(request.asset().chain(), size, rate);
    }

    public void process() {
        worker.drainRemote(WalletQueue.MPC_SIGN, 50, message -> {
            var task = JacksonJson.readValue(json, message.body(), UtxoSigningTask.class);
            QueueTenant.verify(json, message, task.request().tenantId());
            var slots = transaction.execute(status -> {
                if (!queue.lock(WalletQueue.MPC_SIGN, message) || !current(task)) return null;
                return load(task);
            });
            if (slots == null) return new Outcome(task, null, true);
            var signatures = new ArrayList<byte[]>();
            try {
                for (var slot : slots) signatures.add(signing.awaitSignature(task.request().tenantId(), slot.id(), nodes));
            } catch (MpcPendingException pending) { return new Outcome(task, null, false); }
            var payload = payload(slots.getFirst());
            return new Outcome(task, UtxoSigningResult.success(task, signed(payload, signatures)), false);
        }, (message, outcome) -> {
            if (outcome.ignored() || !current(outcome.task())) return true;
            if (outcome.result() == null) return false;
            verifyResult(outcome.task(), outcome.result());
            queue.send(WalletQueue.SIGN_DONE, JacksonJson.writeValue(json, outcome.result()),
                    QueueTenant.headers(outcome.task().request().tenantId()));
            return true;
        });
    }

    /** Independently rebuilds the result from selected, decrypted and verified durable input signatures. */
    public void verifyResult(UtxoSigningTask task, UtxoSigningResult result) {
        requireTransaction();
        require(result.successful() && result.transactionId() == task.transactionId()
                        && result.tenantId().equals(task.request().tenantId())
                        && result.requestId().equals(task.request().requestId())
                        && result.chain().equals(task.request().asset().chain()), "foreign or failed MPC UTXO result");
        var slots = load(task);
        var signatures = slots.stream().map(slot -> signing.signature(result.tenantId(), slot.id())
                .orElseThrow(() -> new IllegalStateException("MPC UTXO input has no selected signature"))).toList();
        require(signed(payload(slots.getFirst()), signatures).equals(result.transaction()),
                "UTXO result differs from the persisted MPC intent and selected signatures");
    }

    private boolean current(UtxoSigningTask task) {
        var parent = transactions.lockById(task.request().asset().chain(), task.transactionId()).orElseThrow();
        require(parent.request().tenantId().equals(task.request().tenantId()), "foreign UTXO queue owner");
        if (parent.terminal() || parent.status() == Constants.SENT || !parent.request().requestId().equals(task.request().requestId())) return false;
        require(parent.request().equals(task.request()), "UTXO queue intent differs from parent");
        return true;
    }

    private List<MpcSignRequest> load(UtxoSigningTask task) {
        var request = task.request();
        var slots = new ArrayList<MpcSignRequest>();
        for (int i = 0; i < request.inputs().size(); i++) slots.add(requests.findBusiness(request.tenantId(),
                ChainType.valueOf(request.asset().chain()), type(request), request.requestId().toString(), slot(i)).orElseThrow());
        require(!slots.isEmpty(), "UTXO input slots missing");
        var payload = payload(slots.getFirst());
        require(payload.task().equals(task) && payload.keyIds().size() == slots.size(), "UTXO payload owner differs");
        var owned = ownedKeys(request);
        var keys = owned.inputs();
        var unsigned = prepare(request, keys);
        require(payload.unsigned().equals(unsigned) && payload.changeKeyId().equals(owned.change().id()), "UTXO payload differs from its canonical batch or joint change key");
        for (int i = 0; i < slots.size(); i++) {
            int originalEpoch=slots.get(i).command().keyEpoch();
            require(originalEpoch<=keys.get(i).activeEpoch() && (originalEpoch==keys.get(i).activeEpoch() || slots.get(i).state()==MpcSignRequest.State.COMPLETED),
                    "only a completed original UTXO signature may be read after its key epoch refresh");
            require(payload.keyIds().get(i).equals(keys.get(i).id())
                            && slots.get(i).command().equals(command(payload, slots.getFirst().command().payloadHex(), keys.get(i), originalEpoch, i)),
                    "UTXO signature slot differs from its input and immutable payload");
        }
        return List.copyOf(slots);
    }

    private UtxoSigningPayload payload(MpcSignRequest slot) {
        require(ENCODING.equals(slot.command().payloadEncoding()), "UTXO payload encoding differs");
        return UtxoSigningPayload.decode(slot.command(),json);
    }

    private OwnedKeys ownedKeys(UtxoSigningRequest request) {
        var profile = profiles.findAccountByChain(request.asset().chain()).orElseThrow();
        require(request.asset().network() == com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork.parse(profile.getNetwork())
                        && request.asset().currencyId() == profile.getRuntimeCurrencyId()
                        && request.asset().coinType() == profile.getBip44CoinType() && request.asset().decimals() == 8
                        && request.dustThreshold() >= profile.getDustThreshold(),
                "UTXO signing asset, network or dust threshold differs from the configured chain");
        var inputAddresses=new ArrayList<ChainAddressRecord>();
        for(var input:request.inputs())inputAddresses.add(addresses.findEnabledByTenantAndAddress(request.tenantId(),request.asset().chain(),request.asset().chain(),input.address()).orElseThrow());
        var change=addresses.findEnabledByTenantAndAddress(request.tenantId(),request.asset().chain(),request.asset().chain(),request.changeAddress()).orElseThrow();
        var ids=new HashSet<UUID>();
        for(var address:inputAddresses) { require(address.getMpcKeyId()!=null,"UTXO input has no joint key");ids.add(address.getMpcKeyId()); }
        require(change.getMpcKeyId()!=null,"UTXO change has no joint key");ids.add(change.getMpcKeyId());
        Map<UUID,MpcKey> locked=new HashMap<>();
        // Input and change roles can reverse between batches; acquire their shared key locks in one stable order.
        for(var id:ids.stream().sorted().toList())locked.put(id,signing.lockActiveKey(request.tenantId(),id,ChainType.valueOf(request.asset().chain())));
        var keys = new ArrayList<MpcKey>();
        for (int i=0;i<request.inputs().size();i++) {
            var input=request.inputs().get(i);var address=inputAddresses.get(i);var key=locked.get(address.getMpcKeyId());
            require(key.userId() == address.getUserId() && key.biz() == address.getBiz() && key.addressIndex() == address.getAddressIndex()
                            && input.key().userId() == key.userId() && input.key().biz() == key.biz() && input.key().index() == key.addressIndex()
                            && input.key().redeemScript() == null
                            && input.address().equals(MpcPublicAddressCodec.encode(profile, HEX.parseHex(key.publicKeyHex()))),
                    "UTXO address differs from the tenant joint key");
            keys.add(key);
        }
        var key=locked.get(change.getMpcKeyId());
        require(key.userId()==change.getUserId() && key.biz()==change.getBiz() && key.addressIndex()==change.getAddressIndex()
                && request.changeAddress().equals(MpcPublicAddressCodec.encode(profile,HEX.parseHex(key.publicKeyHex()))),"UTXO change differs from the tenant's owned joint key");
        return new OwnedKeys(List.copyOf(keys),key);
    }

    private void validateBusiness(UtxoSigningRequest request) {
        String chain = request.asset().chain();
        if (request.operation() == UtxoSigningRequest.Operation.COLLECTION) {
            var owner = collections.lockForSubmission(request.tenantId(), chain, request.collectionNo()).orElseThrow();
            require(request.payments().size() == 1 && request.collectionNo().equals(request.payments().getFirst().reference())
                            && "SIGNING".equals(owner.getStatus()) && chain.equals(owner.getAssetSymbol())
                            && owner.getToAddress().equals(request.payments().getFirst().address())
                            && owner.getAmount().compareTo(request.payments().getFirst().amount()) == 0
                            && request.inputs().stream().allMatch(input -> owner.getFromAddress().equals(input.address())),
                    "UTXO collection differs from its owned business intent");
        } else {
            var references = new HashSet<String>();
            for (var payment : request.payments().stream().sorted(java.util.Comparator.comparing(UtxoSigningRequest.Payment::reference)).toList()) {
                var owner = orders.lockForSettlement(request.tenantId(), chain, payment.reference()).orElseThrow();
                require(references.add(payment.reference()) && "SIGNING".equals(owner.getStatus()) && chain.equals(owner.getAssetSymbol())
                                && owner.getUserId() == payment.userId() && owner.getToAddress().equals(payment.address())
                                && owner.getAmount().compareTo(payment.amount()) == 0 && owner.getFee().compareTo(payment.customerFee()) == 0,
                        "UTXO withdrawal differs from its owned business intent");
            }
        }
        addresses.findEnabledByTenantAndAddress(request.tenantId(), chain, chain, request.changeAddress())
                .orElseThrow(() -> new IllegalArgumentException("UTXO change address must belong to the tenant"));
    }

    private static MpcUtxoTransactionCodec.Unsigned prepare(UtxoSigningRequest request, List<MpcKey> keys) {
        return MpcUtxoTransactionCodec.prepare(request.asset().chain(), request.asset().network(), publicInputs(request, keys),
                request.payments().stream().map(payment -> new MpcUtxoTransactionCodec.Payment(payment.address(),
                        request.asset().atomic(payment.amount()))).toList(), request.changeAddress(), request.feeRate(), request.dustThreshold());
    }

    private static List<MpcUtxoTransactionCodec.Input> publicInputs(UtxoSigningRequest request, List<MpcKey> keys) {
        var inputs = new ArrayList<MpcUtxoTransactionCodec.Input>();
        for (int i = 0; i < keys.size(); i++) {
            var input = request.inputs().get(i);
            inputs.add(new MpcUtxoTransactionCodec.Input(input.txId(), input.vout(), request.asset().atomic(input.amount()), keys.get(i).publicKeyHex()));
        }
        return List.copyOf(inputs);
    }

    private static MpcSignCommand command(UtxoSigningPayload payload, String hex, MpcKey key, int i) {
        return command(payload,hex,key,key.activeEpoch(),i);
    }
    private static MpcSignCommand command(UtxoSigningPayload payload,String hex,MpcKey key,int epoch,int i) {
        var request = payload.task().request();
        return new MpcSignCommand(request.tenantId(), key.id(), epoch, ChainType.valueOf(request.asset().chain()),
                type(request), request.requestId().toString(), slot(i), MpcSignCommand.Purpose.TRANSACTION, ENCODING, hex,
                HEX.formatHex(MpcUtxoTransactionCodec.digest(payload.unsigned(), i)));
    }
    private static MpcSignCommand.BusinessType type(UtxoSigningRequest request) {
        return request.operation() == UtxoSigningRequest.Operation.COLLECTION ? MpcSignCommand.BusinessType.COLLECTION : MpcSignCommand.BusinessType.WITHDRAWAL;
    }
    private static String slot(int i) { return "input/" + i; }
    private static UtxoSignedTransaction signed(UtxoSigningPayload payload, List<byte[]> signatures) {
        var result = MpcUtxoTransactionCodec.assemble(payload.unsigned(), signatures);
        long size = payload.unsigned().estimatedVBytes();
        return new UtxoSignedTransaction(result.transactionHex(), result.txId(), result.feeAtomic(), result.weight(), result.vBytes(),
                new UtxoSignedTransaction.Estimates(size, size, Math.multiplyExact(size, 4)));
    }
    private static void requireTransaction() { require(TransactionSynchronizationManager.isActualTransactionActive(), "UTXO signing metadata requires a transaction"); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private record Outcome(UtxoSigningTask task, UtxoSigningResult result, boolean ignored) { }
    private record OwnedKeys(List<MpcKey> inputs,MpcKey change) { }
}
