package com.surprising.wallet.service;

import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.model.mpc.MpcSignRequest;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;
import java.math.BigInteger;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bitcoinj.crypto.ECKey;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;
import tools.jackson.databind.*;

/** Proof persistence and the original nonce admission fence commit together; this service never submits a protocol. */
@Service @RequiredArgsConstructor
public class MpcNonceAdmissionService {
    private final EvmNonceRepository nonces;private final AccountSequenceRepository sequences;private final WalletMpcKeyRepository keys;
    private final WalletMpcSignRequestRepository requests;private final WalletMpcSignAttemptRepository attempts;private final ObjectMapper json;
    public void admit(MpcSignCommand command,SigningAuthorization.Claims claims,String issuerPublicKey) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("nonce admission requires the original proof transaction");
        var allocation=allocation(command);if(allocation.isEmpty())return;var value=allocation.get();String chain=command.chain().name(),owner=command.reservationOwner();
        boolean admitted=value.evm()?nonces.admit(chain,value.address(),value.nonce(),owner):sequences.admit(chain,value.address(),value.nonce().longValueExact(),owner);
        if(admitted)return;
        if(value.evm()?nonces.hasPendingOwner(chain,owner):sequences.hasPendingOwner(chain,owner))throw new IllegalStateException("proof wire differs from its original unadmitted nonce allocation");
        // Another slot/session may already have admitted this exact whole transaction. Check its real issuer signature.
        for(var id:requests.businessSlots(command.tenantId(),chain,command.businessType(),command.businessReference())) {
            var previous=requests.find(command.tenantId(),id).orElseThrow();
            if(previous.state()==MpcSignRequest.State.CANCELLED || !ApprovalTransactionHash.version(previous.command()).equals(ApprovalTransactionHash.version(command)))continue;
            for(var attempt:attempts.history(command.tenantId(),id))if(attempt.authorization()!=null) {
                var original=SigningAuthorization.verifyOriginalProof(attempt.authorization(),SigningAuthorization.publicKey(issuerPublicKey),attempt.request());
                if(original.businessHash().equals(claims.businessHash()) && original.chain().equals(claims.chain()) && original.network().equals(claims.network())
                        && original.purpose().equals(claims.purpose()))return;
            }
        }
        throw new IllegalStateException("first independent proof has no original nonce allocation");
    }
    private Optional<Allocation> allocation(MpcSignCommand command) {
        if(command.payloadEncoding().equals(Evm7702SigningPayload.ENCODING)) {
            var plan=Evm7702SigningPayload.decode(command,json).plan();String address=evmAddress(command.tenantId(),plan.relayer().getMpcKeyId());
            if(!plan.relayer().getTenantId().equals(command.tenantId()) || !plan.relayer().getAddress().equalsIgnoreCase(address))throw new IllegalStateException("original relayer identity differs");
            return Optional.of(new Allocation(true,address,plan.relayerNonce()));
        }
        if(command.chain().isEvm())return Optional.of(new Allocation(true,evmAddress(command.tenantId(),command.keyId()),payload(command,EvmSigningPayload.class).raw().getNonce()));
        if(command.payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON")) {var p=payload(command,NearRegistrationPayload.class).unsigned();return account(p.sender(),p.nonce());}
        return switch(command.chain()) {
            case APTOS -> {var p=payload(command,AptosSigningPayload.class).unsigned();yield account(p.sender(),p.sequence());}
            case TON -> {var p=payload(command,TonSigningPayload.class).unsigned();yield account(p.sender(),p.sequence());}
            case XRP -> {var p=MpcXrpTransactionCodec.restore(payload(command,XrpSigningPayload.class).unsigned());yield account(p.account().value(),p.sequence().longValue());}
            case NEAR -> {var p=payload(command,NearSigningPayload.class).unsigned();yield account(p.sender(),p.nonce());}
            case DOT -> {var p=payload(command,PolkadotSigningPayload.class).unsigned();yield account(p.role().name()+":"+p.sender(),p.nonce());}
            default -> Optional.empty();
        };
    }
    private String evmAddress(UUID tenant,UUID id) {
        var key=keys.find(tenant,id).orElseThrow();if(!key.keyGroup().equals("EVM") || !key.algorithm().equals("ECDSA_SECP256K1"))throw new IllegalStateException("nonce owner joint key differs");
        return "0x"+Keys.getAddress(Sign.publicFromPoint(ECKey.fromPublicOnly(HexFormat.of().parseHex(key.publicKeyHex())).decompress().getPubKey()));
    }
    private <T>T payload(MpcSignCommand command,Class<T> type){return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(HexFormat.of().parseHex(command.payloadHex()));}
    private static Optional<Allocation> account(String address,long sequence){return Optional.of(new Allocation(false,address,BigInteger.valueOf(sequence)));}
    private record Allocation(boolean evm,String address,BigInteger nonce) { }
}
