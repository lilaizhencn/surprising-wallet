package com.surprising.wallet.approval.chain;

import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.SigningCandidate;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcSignCommand;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Explicit transaction-family allowlist. Unimplemented families cannot fall through to a generic digest signer. */
@Component
public class ApprovalWithdrawalVerifiers {
    private final ObjectMapper json;
    private final ApprovalChainRpcClient rpc;
    public ApprovalWithdrawalVerifiers(ObjectMapper json,ApprovalChainRpcClient rpc) { this.json=json;this.rpc=rpc; }
    public boolean supports(MpcSignCommand command) {
        if(command.businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION)return command.chain()==ChainType.XRP && command.payloadEncoding().equals("XRPL_TX_JSON")
                && command.purpose()==MpcSignCommand.Purpose.TRANSACTION && command.signingSlot().equals("transaction");
        if(command.chain()==ChainType.NEAR && command.payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON"))return command.businessType()==MpcSignCommand.BusinessType.FUNDING
                && command.purpose()==MpcSignCommand.Purpose.TRANSACTION && command.signingSlot().equals("storage-registration");
        if(command.chain().isEvm() && command.payloadEncoding().equals(com.surprising.wallet.common.signing.Evm7702SigningPayload.ENCODING)) {
            if(command.businessType()==MpcSignCommand.BusinessType.COLLECTION) {
                if(command.signingSlot().equals("relayer"))return command.purpose()==MpcSignCommand.Purpose.TRANSACTION;
                return command.signingSlot().matches("authorization-(?:0|[1-9][0-9]?)")?command.purpose()==MpcSignCommand.Purpose.EIP7702_AUTHORIZATION
                        :command.signingSlot().matches("operation-(?:0|[1-9][0-9]?)") && command.purpose()==MpcSignCommand.Purpose.EIP7702_OPERATION;
            }
            if(command.businessType()!=MpcSignCommand.BusinessType.WITHDRAWAL)return false;
            return switch(command.signingSlot()) {
                case "authorization-0" -> command.purpose()==MpcSignCommand.Purpose.EIP7702_AUTHORIZATION;
                case "operation-0" -> command.purpose()==MpcSignCommand.Purpose.EIP7702_OPERATION;
                case "relayer" -> command.purpose()==MpcSignCommand.Purpose.TRANSACTION;
                default -> false;
            };
        }
        if(command.purpose()!=MpcSignCommand.Purpose.TRANSACTION)return false;
        if(command.chain().isBitcoinLike())return command.payloadEncoding().equals("UTXO_MPC_JSON_V1") && command.signingSlot().matches("input/(?:0|[1-9][0-9]{0,2})");
        if(command.chain()==ChainType.SOLANA)return List.of("transaction","fee_payer").contains(command.signingSlot()) && command.payloadEncoding().equals("SOLANA_TX_JSON");
        if(!command.signingSlot().equals("transaction"))return false;
        String encoding=command.chain().isEvm()?"EVM_TX_JSON":switch(command.chain()) {
            case APTOS -> "APTOS_TX_JSON";
            case TON -> "TON_V4R2_PAYMENT_JSON";
            case TRON -> "TRON_TX_JSON";
            case XRP -> "XRPL_TX_JSON";
            case NEAR -> "NEAR_BORSH_PAYMENT_JSON";
            case HYPERCORE -> "HYPERCORE_JSON";
            case XMR -> "MONERO_NATIVE_PAYMENT_JSON";
            case SUI -> "SUI_PTB_PAYMENT_JSON";
            case ADA -> "CARDANO_PAYMENT_JSON";
            case DOT -> "POLKADOT_SCALE_PAYMENT_JSON";
            default -> "";
        };
        return command.payloadEncoding().equals(encoding);
    }
    public List<String> additionalPublicKeys(SigningCandidate candidate) {
        return candidate.command().chain()==ChainType.SOLANA?SolanaWithdrawalVerifier.publicKeys(candidate,json):List.of();
    }
    public List<UUID> additionalKeyIds(SigningCandidate candidate) {
        return candidate.command().payloadEncoding().equals(com.surprising.wallet.common.signing.Evm7702SigningPayload.ENCODING)
                ?candidate.command().businessType()==MpcSignCommand.BusinessType.COLLECTION?Evm7702CollectionVerifier.keyIds(candidate,json):Evm7702WithdrawalVerifier.keyIds(candidate,json):List.of();
    }
    /** Read-only HTTP runs before the final transaction. Only the persisted original facts are reused for later signature slots. */
    public tools.jackson.databind.JsonNode observe(ApprovalRequest review,SigningCandidate candidate,Instant now) {
        var facts=candidate.command().payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON")?NearRegistrationVerifier.observe(review,candidate,rpc,json)
                :candidate.command().payloadEncoding().equals(com.surprising.wallet.common.signing.Evm7702SigningPayload.ENCODING)
                ?candidate.command().businessType()==MpcSignCommand.BusinessType.COLLECTION?Evm7702CollectionVerifier.observe(review,candidate,rpc,json):Evm7702WithdrawalVerifier.observe(review,candidate,rpc,json):switch(candidate.command().chain()) {
            case SOLANA -> SolanaWithdrawalVerifier.observe(review,candidate,rpc,json);
            case TON -> TonWithdrawalVerifier.observe(review,candidate,rpc,json);
            case TRON -> TronWithdrawalVerifier.observe(review,candidate,rpc,json);
            case XRP -> candidate.command().businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION?XrpPreparationVerifier.observe(review,candidate,rpc,json):XrpWithdrawalVerifier.observe(review,candidate,rpc,json);
            case NEAR -> NearWithdrawalVerifier.observe(review,candidate,rpc,json);
            case HYPERCORE -> HyperCoreWithdrawalVerifier.observe(review,candidate,rpc,json);
            case SUI -> SuiWithdrawalVerifier.observe(review,candidate,rpc,json);
            case ADA -> CardanoWithdrawalVerifier.observe(review,candidate,rpc,json);
            case DOT -> PolkadotWithdrawalVerifier.observe(review,candidate,rpc,json);
            default -> null;
        };
        if(candidate.command().chain().isEvm()) {
            if(facts==null)facts=json.createObjectNode();
            ((tools.jackson.databind.node.ObjectNode)facts).set("evmFees",EvmFeePolicy.observe(review,candidate,rpc,json));
        }
        if(facts==null)return null;
        return json.createObjectNode().put("chain",review.assetSnapshot().chain()).put("networkIdentity",review.assetSnapshot().networkIdentity())
                .put("observedAt",now.toString()).set("facts",facts);
    }
    public Verified verifyBatch(List<ApprovalRequest> reviewed,RegisteredMpcKey key,Map<UUID,RegisteredMpcKey> registered,SigningCandidate candidate,Instant now,tools.jackson.databind.JsonNode evidence) {
        if(candidate.command().payloadEncoding().equals(com.surprising.wallet.common.signing.Evm7702SigningPayload.ENCODING)) {
            require(evidence!=null && evidence.path("chain").asText().equals(reviewed.getFirst().assetSnapshot().chain())
                    && evidence.path("networkIdentity").asText().equals(reviewed.getFirst().assetSnapshot().networkIdentity()),"EIP-7702 original independent observation is missing or differs");
            return candidate.command().businessType()==MpcSignCommand.BusinessType.COLLECTION?Evm7702CollectionVerifier.verify(reviewed,registered,candidate,evidence.path("facts"),now,json)
                    :Evm7702WithdrawalVerifier.verify(reviewed,registered,candidate,evidence.path("facts"),now,json);
        }
        require(reviewed.size()==1,"this transaction family requires one reviewed payment");
        return verify(reviewed.getFirst(),key,registered,candidate,now,evidence);
    }
    public Verified verify(ApprovalRequest reviewed,RegisteredMpcKey key,Map<UUID,RegisteredMpcKey> registered,SigningCandidate candidate,Instant now,tools.jackson.databind.JsonNode evidence) {
        require(supports(candidate.command()) && !candidate.command().chain().isBitcoinLike(),"transaction family requires its independent batch withdrawal verifier");
        if(candidate.command().chain()==ChainType.APTOS)return AptosWithdrawalVerifier.verify(reviewed,key,candidate,now,json);
        var facts=evidence==null?null:evidence.path("facts");
        if(evidence!=null)require(evidence.path("chain").asText().equals(reviewed.assetSnapshot().chain())
                && evidence.path("networkIdentity").asText().equals(reviewed.assetSnapshot().networkIdentity()),"persisted independent chain observation belongs to another domain");
        if(candidate.command().payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON"))return NearRegistrationVerifier.verify(reviewed,key,candidate,facts,json);
        switch(candidate.command().chain()) {
            case SOLANA: return SolanaWithdrawalVerifier.verify(reviewed,key,registered,candidate,facts,json);
            case TON: return TonWithdrawalVerifier.verify(reviewed,key,candidate,facts,now,json);
            case TRON: return TronWithdrawalVerifier.verify(reviewed,key,candidate,facts,now,json);
            case XRP: return candidate.command().businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION?XrpPreparationVerifier.verify(reviewed,key,registered,candidate,facts,json):XrpWithdrawalVerifier.verify(reviewed,key,candidate,facts,json);
            case NEAR: return NearWithdrawalVerifier.verify(reviewed,key,candidate,facts,json);
            case HYPERCORE: return HyperCoreWithdrawalVerifier.verify(reviewed,key,candidate,facts,now,json);
            case XMR: return MoneroWithdrawalVerifier.verify(reviewed,key,candidate,json);
            case SUI: return SuiWithdrawalVerifier.verify(reviewed,key,candidate,facts,json);
            case ADA: return CardanoWithdrawalVerifier.verify(reviewed,key,candidate,facts,json);
            case DOT: return PolkadotWithdrawalVerifier.verify(reviewed,key,candidate,facts,json);
            default: require(candidate.command().chain().isEvm(),"explicit chain verifier required");
        }
        var intent=reviewed.intent();var asset=reviewed.assetSnapshot();var command=candidate.command();var execution=candidate.execution();
        long chainId=Long.parseLong(asset.networkIdentity().substring(7));
        var envelope=json.reader().with(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .readTree(HexFormat.of().parseHex(command.payloadHex()));
        require(envelope.isObject() && envelope.size()==7 && envelope.path("chainId").isIntegralNumber()
                && envelope.path("chainId").bigIntegerValue().equals(BigInteger.valueOf(chainId))
                && envelope.path("symbol").asText().equals(asset.symbol())
                && envelope.path("assetDecimals").isIntegralNumber() && envelope.path("assetDecimals").bigIntegerValue().equals(BigInteger.valueOf(asset.decimals()))
                && envelope.path("nativeDecimals").isIntegralNumber() && envelope.path("nativeDecimals").bigIntegerValue().equals(BigInteger.valueOf(asset.nativeDecimals()))
                && envelope.has("contract") && (asset.tokenIdentifier()==null?envelope.path("contract").isNull():asset.tokenIdentifier().equalsIgnoreCase(envelope.path("contract").asText()))
                && envelope.path("amount").isNumber() && envelope.path("amount").decimalValue().compareTo(intent.amount())==0
                && envelope.path("unsignedHex").isString(),"EVM envelope differs from the independently reviewed asset and amount");
        var verified=EvmWithdrawalVerifier.verify(intent,new EvmWithdrawalVerifier.RegisteredAsset(asset.chain(),asset.network(),chainId,
                asset.symbol(),asset.tokenIdentifier(),asset.decimals(),asset.nativeDecimals(),asset.networkFeeLimit()),
                new EvmWithdrawalVerifier.RegisteredKey(key.tenantId(),key.keyId(),key.epoch(),key.publicKeyHex()),execution,envelope.path("unsignedHex").asText());
        String domain="EVM:"+asset.networkIdentity()+":"+verified.sender()+":"+verified.nonce();
        var total=EvmFeePolicy.verify(List.of(reviewed),candidate,facts==null?null:facts.path("evmFees"),new BigInteger(verified.maximumFeeAtomic()),json);
        return new Verified(domain,total,true,List.of(Evm7702WithdrawalVerifier.lock(domain)));
    }
    public record Verified(String spendDomain,BigInteger maximumFeeAtomic,boolean supportsExactRecovery,List<com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Outpoint> inputs,boolean preparationVerified) {
        public Verified { inputs=List.copyOf(inputs); }
        public Verified(String spendDomain,BigInteger maximumFeeAtomic,boolean supportsExactRecovery){this(spendDomain,maximumFeeAtomic,supportsExactRecovery,List.of());}
        public Verified(String spendDomain,BigInteger maximumFeeAtomic,boolean supportsExactRecovery,List<com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Outpoint> inputs){this(spendDomain,maximumFeeAtomic,supportsExactRecovery,inputs,false);}
    }
    private static void require(boolean value,String message) { if(!value)throw new IllegalArgumentException(message); }
}
