package com.surprising.wallet.service;

import com.surprising.wallet.chain.MpcPublicAddressCodec;
import com.surprising.wallet.chain.model.EvmTransactionRecord;
import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.chain.AccountChainProfile;
import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.model.mpc.EvmSigningPayload;
import com.surprising.wallet.model.mpc.MpcPendingException;
import com.surprising.wallet.model.mpc.MpcSignCommand;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.sdk.signing.MpcEvmTransactionCodec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Nonce/unsigned intent commit -> remote MPC -> signed raw/business hash commit -> chain RPC. */
@Service
@RequiredArgsConstructor
public class EvmSubmissionService {
    private static final String SLOT = "transaction";
    private static final String ENCODING = "EVM_TX_JSON";
    private final WithdrawalOrderRepository withdrawals;
    private final CollectionRecordRepository collections;
    private final EvmTransactionRepository transactions;
    private final EvmNonceRepository nonces;
    private final CustodyAssetRecoveryRepository recoveries;
    private final WalletMpcSignRequestRepository requests;
    private final MpcSigningService signing;
    private final MpcNodes nodes;
    private final ObjectMapper json;
    private final ChainProfileRepository profiles;

    public enum Kind { WITHDRAWAL, COLLECTION, RECOVERY }
    public record Operation(UUID tenantId, String reference, Kind kind) {
        public Operation {
            Objects.requireNonNull(tenantId, "EVM operation tenant is required");
            Objects.requireNonNull(kind, "EVM operation kind is required");
            if (reference == null || reference.isBlank()) throw new IllegalArgumentException("EVM operation reference is required");
        }
    }

    public Optional<MpcSignRequest> intent(Operation operation, String chain) {
        return requests.findBusiness(operation.tenantId(), ChainType.valueOf(chain),
                MpcSignCommand.BusinessType.valueOf(operation.kind().name()), operation.reference(), SLOT);
    }

    public EvmTransactionRecord existing(Operation operation, String chain) {
        String hash = switch (operation.kind()) {
            case WITHDRAWAL -> withdrawals.findTxHash(chain, operation.reference(), operation.tenantId()).orElse(null);
            case COLLECTION -> collections.findTxHash(operation.tenantId(), chain, operation.reference()).orElse(null);
            case RECOVERY -> recoveries.require(operation.tenantId(), UUID.fromString(operation.reference())).recoveryTxHash();
        };
        if (hash == null) return null;
        require(intent(operation, chain).isPresent(), "owned EVM MPC signing intent is missing");
        return requireSigned(chain, hash);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public MpcSignRequest reserve(Operation operation, String chain, ChainAddressRecord sender, BigInteger chainNonce,
                                  UnsignedTransactionFactory factory) {
        require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "EVM submission phase requires a transaction");
        var owner = lockOwner(operation, chain);
        require(operation.tenantId().equals(sender.getTenantId()) && chain.equals(sender.getChain()) && owner.sender().equalsIgnoreCase(sender.getAddress()),
                "EVM sender differs from owned operation");
        var existing = intent(operation, chain);
        if (existing.isPresent()) {
            require(existing.get().command().keyId().equals(sender.getMpcKeyId()), "EVM intent key differs from sender");
            validate(owner, payload(existing.get()));
            return existing.get();
        }
        require(owner.hash() == null && "SIGNING".equals(owner.state()), "EVM operation is not signing");
        var type = ChainType.valueOf(chain);
        require(type.isEvm(), "EVM signing requires an EVM chain");
        var key = signing.lockActiveKey(operation.tenantId(), Objects.requireNonNull(sender.getMpcKeyId(), "EVM sender has no MPC key"), type);
        String publicAddress = MpcPublicAddressCodec.encode(AccountChainProfile.builder().chain(chain).network("unused").build(),
                HexFormat.of().parseHex(key.publicKeyHex()));
        require(publicAddress.equalsIgnoreCase(owner.sender()), "EVM sender does not match its joint public key");
        var nonce = nonces.reserve(chain, sender.getAddress().toLowerCase(Locale.ROOT), chainNonce);
        var unsigned = factory.create(nonce);
        require(unsigned.raw().getNonce().equals(nonce)
                        && unsigned.chainId() == profiles.findAccountByChain(chain).orElseThrow().getChainId(),
                "EVM payload nonce or chain ID differs from its reservation");
        validate(owner, unsigned);
        var command = new MpcSignCommand(operation.tenantId(), key.id(), key.activeEpoch(), type,
                MpcSignCommand.BusinessType.valueOf(operation.kind().name()), operation.reference(), SLOT,
                MpcSignCommand.Purpose.TRANSACTION, ENCODING, HexFormat.of().formatHex(json.writeValueAsBytes(unsigned)),
                HexFormat.of().formatHex(MpcEvmTransactionCodec.digest(unsigned.raw(), unsigned.chainId())));
        return signing.reserveSignature(command, nodes.online());
    }

    /** Caller must be outside every transaction; only the fixed, committed intent is sent to nodes. */
    public void sign(UUID tenant, UUID requestId) { signing.awaitSignature(tenant, requestId, nodes); }

    /** A missing hash alone is insufficient proof: fence the owner and check its durable MPC receipt. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public int releaseUnsigned(Operation operation, String chain) {
        require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "EVM submission phase requires a transaction");
        var owner = lockOwner(operation, chain);
        if (owner.hash() != null || intent(operation, chain).isPresent()) return 0;
        return switch (operation.kind()) {
            case WITHDRAWAL -> withdrawals.recoverUnsignedDurable(operation.tenantId(), chain, operation.reference());
            case COLLECTION -> collections.recoverUnsignedDurable(operation.tenantId(), chain, operation.reference());
            case RECOVERY -> recoveries.recoverUnsignedExecution(operation.tenantId(), UUID.fromString(operation.reference()));
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public void recoveryError(Operation operation, String chain, String reason) {
        require(operation.kind() == Kind.RECOVERY, "recovery error requires a recovery operation");
        require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "EVM submission phase requires a transaction");
        var owner = lockOwner(operation, chain);
        UUID id = UUID.fromString(operation.reference());
        if (!"SIGNING".equals(owner.state())) return;
        if (owner.hash() == null && intent(operation, chain).isPresent()) {
            recoveries.executionPending(id, reason);
        } else {
            recoveries.executionFailed(id, reason);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Throwable.class)
    public EvmTransactionRecord commit(Operation operation, String chain, UUID requestId) {
        require(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive(),
                "EVM submission phase requires a transaction");
        var owner = lockOwner(operation, chain);
        var request = intent(operation, chain).orElseThrow();
        require(request.id().equals(requestId) && request.state() == MpcSignRequest.State.COMPLETED,
                "EVM signing request is not the completed owned intent");
        var payload = payload(request);
        validate(owner, payload);
        var key = signing.requireKey(operation.tenantId(), request.command().keyId());
        String sender = MpcPublicAddressCodec.encode(AccountChainProfile.builder().chain(chain).network("unused").build(),
                HexFormat.of().parseHex(key.publicKeyHex()));
        require(sender.equalsIgnoreCase(owner.sender()), "completed EVM key differs from owned sender");
        byte[] signature = signing.signature(operation.tenantId(), request.id())
                .orElseThrow(() -> new MpcPendingException(request.id(), request.state().name()));
        String raw = org.web3j.utils.Numeric.toHexString(MpcEvmTransactionCodec.assemble(
                HexFormat.of().parseHex(payload.unsignedHex()), payload.chainId(), HexFormat.of().parseHex(key.publicKeyHex()), signature));
        String hash = org.web3j.crypto.Hash.sha3(raw);
        if (owner.hash() != null) {
            require(owner.hash().equalsIgnoreCase(hash), "owned EVM hash differs from the selected signature");
            return requireSigned(chain, hash);
        }
        require("SIGNING".equals(owner.state()) || "BROADCAST_UNKNOWN".equals(owner.state()), "EVM operation cannot bind signed bytes");
        var transaction = payload.raw();
        var signed = EvmTransactionRecord.builder().chain(chain).txHash(hash).fromAddress(sender)
                .toAddress(transaction.getTo().toLowerCase(Locale.ROOT)).assetSymbol(payload.symbol()).contractAddress(payload.contract())
                .amount(payload.amount()).fee(new BigDecimal(transaction.getGasLimit().multiply(payload.maxFeePerGas()))
                        .movePointLeft(payload.nativeDecimals()).setScale(18, RoundingMode.UP))
                .nonce(transaction.getNonce().longValueExact()).confirmations(0).status("PREPARED").rawPayload(raw).build();
        transactions.upsert(signed);
        int bound = switch (operation.kind()) {
            case WITHDRAWAL -> withdrawals.bindPrepared(operation.tenantId(), chain, operation.reference(), hash);
            case COLLECTION -> collections.bindPrepared(operation.tenantId(), chain, operation.reference(), hash);
            case RECOVERY -> recoveries.bindPrepared(operation.tenantId(), UUID.fromString(operation.reference()), hash);
        };
        require(bound == 1, "EVM operation changed before signed bytes were committed");
        return signed;
    }

    public EvmSigningPayload payload(MpcSignRequest request) {
        require(ENCODING.equals(request.command().payloadEncoding()) && request.command().purpose() == MpcSignCommand.Purpose.TRANSACTION,
                "unsupported EVM intent payload");
        var payload = json.readValue(HexFormat.of().parseHex(request.command().payloadHex()), EvmSigningPayload.class);
        require(HexFormat.of().formatHex(MpcEvmTransactionCodec.digest(payload.raw(), payload.chainId())).equals(request.command().preimageHex()),
                "persisted EVM envelope differs from the accepted signing digest");
        return payload;
    }

    private Owner lockOwner(Operation operation, String chain) {
        return switch (operation.kind()) {
            case WITHDRAWAL -> {
                var owner = withdrawals.lockForSettlement(operation.tenantId(), chain, operation.reference()).orElseThrow();
                yield new Owner(owner.getStatus(), owner.getFromAddress(), owner.getTxHash(), owner.getAssetSymbol(), owner.getAmount(), owner.getToAddress());
            }
            case COLLECTION -> {
                var owner = collections.lockForSubmission(operation.tenantId(), chain, operation.reference()).orElseThrow();
                yield new Owner(owner.getStatus(), owner.getFromAddress(), owner.getTxHash(), owner.getAssetSymbol(), owner.getAmount(), owner.getToAddress());
            }
            case RECOVERY -> {
                var owner = recoveries.lockForSubmission(operation.tenantId(), UUID.fromString(operation.reference())).orElseThrow();
                require(chain.equals(owner.actualChain()), "recovery chain differs from operation");
                yield new Owner("EXECUTING".equals(owner.status()) ? "SIGNING" : owner.status(), owner.destinationAddress(),
                        owner.recoveryTxHash(), owner.assetSymbol(), owner.verifiedAmount(), owner.recoveryAddress());
            }
        };
    }

    private static void validate(Owner owner, EvmSigningPayload payload) {
        require(owner.symbol().equalsIgnoreCase(payload.symbol()) && owner.amount().compareTo(payload.amount()) == 0,
                "EVM payload asset or amount differs from owned operation");
        var raw = payload.raw();
        BigInteger atomic = payload.amount().movePointRight(payload.assetDecimals()).toBigIntegerExact();
        if (payload.contract() == null) {
            require(owner.to().equalsIgnoreCase(raw.getTo()) && raw.getValue().equals(atomic) && raw.getData().isEmpty(),
                    "EVM native payment differs from owned operation");
        } else {
            String expectedData = "a9059cbb" + org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(org.web3j.utils.Numeric.toBigInt(owner.to()), 64)
                    + org.web3j.utils.Numeric.toHexStringNoPrefixZeroPadded(atomic, 64);
            require(payload.contract().equalsIgnoreCase(raw.getTo()) && raw.getValue().signum() == 0 && expectedData.equalsIgnoreCase(raw.getData()),
                    "EVM token payment differs from owned operation");
        }
    }

    private EvmTransactionRecord requireSigned(String chain, String hash) {
        var signed = transactions.findSigned(chain, hash).orElseThrow(() -> new IllegalStateException("owned EVM signed transaction is missing"));
        require(org.web3j.crypto.Hash.sha3(signed.getRawPayload()).equalsIgnoreCase(hash), "stored EVM raw transaction hash mismatch");
        return signed;
    }
    private record Owner(String state, String sender, String hash, String symbol, BigDecimal amount, String to) { }
    @FunctionalInterface public interface UnsignedTransactionFactory { EvmSigningPayload create(BigInteger nonce); }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
}
