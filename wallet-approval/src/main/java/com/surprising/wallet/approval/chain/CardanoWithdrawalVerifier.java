package com.surprising.wallet.approval.chain;

import static com.surprising.wallet.approval.chain.WithdrawalWireChecks.*;
import com.surprising.wallet.approval.gateway.ApprovalChainRpcClient;
import com.surprising.wallet.approval.gateway.ApprovalUtxoRpcClient.Outpoint;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.*;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;
import com.google.protobuf.ByteString;
import java.math.BigInteger;
import java.util.*;
import utxorpc.v1beta.cardano.Cardano;
import utxorpc.v1beta.query.*;
import utxorpc.v1beta.sync.*;
import tools.jackson.databind.*;

/** Canonical key-only CBOR payment, independently fetched unspent outputs and protocol/network parameters. */
final class CardanoWithdrawalVerifier {
    static JsonNode observe(ApprovalRequest review,SigningCandidate candidate,ApprovalChainRpcClient rpc,ObjectMapper json) {
        var p=payload(candidate,CardanoSigningPayload.class,json);var result=json.createObjectNode();
        try(var reads=rpc.grpc(review.assetSnapshot())) {
            var query=QueryServiceGrpc.newBlockingStub(reads.channel()).withDeadline(reads.deadline());
            var sync=SyncServiceGrpc.newBlockingStub(reads.channel()).withDeadline(reads.deadline());
            var request=Query.ReadUtxosRequest.newBuilder();for(var input:p.unsigned().inputs())request.addKeys(Query.TxoRef.newBuilder().setHash(ByteString.copyFrom(HexFormat.of().parseHex(input.transactionId()))).setIndex(input.index()));
            result.put("genesis",encode(query.readGenesis(Query.ReadGenesisRequest.getDefaultInstance())));
            result.put("parameters",encode(query.readParams(Query.ReadParamsRequest.getDefaultInstance())));
            result.put("tip",encode(sync.readTip(Sync.ReadTipRequest.getDefaultInstance())));
            result.put("utxos",encode(query.readUtxos(request.build())));
        }
        return result;
    }
    static ApprovalWithdrawalVerifiers.Verified verify(ApprovalRequest review,RegisteredMpcKey key,SigningCandidate candidate,JsonNode facts,ObjectMapper json) {
        owner(review,key,candidate,MpcClient.Algorithm.ED25519,json);var p=payload(candidate,CardanoSigningPayload.class,json);var asset=review.assetSnapshot();var u=p.unsigned();
        amount(review,p.symbol(),p.amount(),p.customerFee(),p.decimals());require(asset.nativeSymbol().equals("ADA") && asset.nativeDecimals()==6
                && u.publicKeyHex().equals(key.publicKeyHex()) && u.recipient().equals(review.intent().recipient()) && u.amount().equals(atomic(review))
                && u.unit().equals(asset.tokenIdentifier()==null?"lovelace":MpcCardanoTransactionCodec.assetUnit(asset.tokenIdentifier())),"Cardano exact principal, unit, key or recipient differs");
        require(facts!=null,"Cardano independent node facts are required");
        try {
            var genesis=Query.ReadGenesisResponse.parseFrom(bytes(facts,"genesis"));var parameters=Query.ReadParamsResponse.parseFrom(bytes(facts,"parameters"));
            var tip=Sync.ReadTipResponse.parseFrom(bytes(facts,"tip"));var outputs=Query.ReadUtxosResponse.parseFrom(bytes(facts,"utxos"));
            require(genesis.hasCardano() && genesis.getGenesis().size()==32 && u.genesisHash().equals(HexFormat.of().formatHex(genesis.getGenesis().toByteArray()))
                    && asset.networkIdentity().equals("cardano:"+u.genesisHash()+":"+u.networkMagic())
                    && u.networkMagic()==Integer.toUnsignedLong(genesis.getCardano().getNetworkMagic()),"Cardano independent genesis/network magic differs");
            boolean mainnet="Mainnet".equalsIgnoreCase(genesis.getCardano().getNetworkId());
            require((mainnet || "Testnet".equalsIgnoreCase(genesis.getCardano().getNetworkId()))
                    && u.sender().equals(MpcCardanoTransactionCodec.address(HexFormat.of().parseHex(key.publicKeyHex()),mainnet)),"Cardano address network differs");
            require(tip.hasTip() && tip.getTip().getHash().size()==32 && tip.getTip().getSlot()>=MpcCardanoTransactionCodec.validFrom(u)
                    && tip.getTip().getSlot()<MpcCardanoTransactionCodec.expiry(u)
                    && MpcCardanoTransactionCodec.expiry(u)-MpcCardanoTransactionCodec.validFrom(u)<=7200,"Cardano original CBOR validity interval differs from independent tip");
            var expected=new HashMap<String,MpcCardanoTransactionCodec.Input>();for(var input:u.inputs())require(expected.put(input.transactionId()+":"+input.index(),input)==null,"duplicate Cardano input");
            var seen=new HashSet<String>();require(outputs.getItemsCount()==expected.size(),"Cardano input is spent or absent from independent ledger state");
            for(var output:outputs.getItemsList()) {
                String ref=HexFormat.of().formatHex(output.getTxoRef().getHash().toByteArray())+":"+output.getTxoRef().getIndex();var original=expected.get(ref);
                require(original!=null && seen.add(ref) && original.outputHex().equals(HexFormat.of().formatHex(output.getNativeBytes().toByteArray())),"Cardano original UTxO bytes differ from independently fetched live output");
            }
            require(parameters.hasValues() && parameters.getValues().hasCardano(),"Cardano protocol parameters are missing");var protocol=parameters.getValues().getCardano();
            require(protocol.getProtocolVersion().getMajor()>=9 && protocol.getProtocolVersion().getMajor()<=10
                    && protocol.getMaxTxSize()>0 && MpcCardanoTransactionCodec.signedSize(u)<=protocol.getMaxTxSize(),"Cardano key-only protocol or transaction size is unsupported");
            BigInteger networkFee=MpcCardanoTransactionCodec.fee(u);BigInteger minimum=integer(protocol.getMinFeeCoefficient()).multiply(BigInteger.valueOf(MpcCardanoTransactionCodec.signedSize(u))).add(integer(protocol.getMinFeeConstant()));
            require(networkFee.compareTo(minimum)>=0,"Cardano exact wire fee is below independent protocol minimum");
            // Token outputs require ADA. That value also leaves the tenant and consumes the reviewed native-expenditure allowance.
            BigInteger expenditure=networkFee.add(asset.tokenIdentifier()==null?BigInteger.ZERO:MpcCardanoTransactionCodec.recipientLovelace(u));
            message(candidate,MpcCardanoTransactionCodec.signingBytes(u));var points=u.inputs().stream().map(i->new Outpoint(i.transactionId(),i.index())).sorted(Comparator.comparing(Outpoint::txId).thenComparingInt(Outpoint::index)).toList();
            return new ApprovalWithdrawalVerifiers.Verified("ADA:"+asset.networkIdentity()+":"+SigningAuthorization.hash(json.writeValueAsBytes(points)),fee(review,expenditure),true,points);
        } catch(com.google.protobuf.InvalidProtocolBufferException invalid){throw new IllegalArgumentException("invalid persisted independent Cardano facts",invalid);}
    }
    private static String encode(com.google.protobuf.MessageLite value){return Base64.getEncoder().encodeToString(value.toByteArray());}
    private static byte[] bytes(JsonNode facts,String field){require(facts.path(field).isString(),"missing independent Cardano read receipt");return Base64.getDecoder().decode(facts.path(field).asText());}
    private static BigInteger integer(Cardano.BigInt value) {
        var number=switch(value.getBigIntCase()) {
            case INT -> BigInteger.valueOf(value.getInt());
            case BIG_U_INT -> new BigInteger(1,value.getBigUInt().toByteArray());
            default -> throw new IllegalArgumentException("Cardano protocol fee integer is missing or negative");
        };
        require(number.signum()>=0,"Cardano protocol fee integer is negative");return number;
    }
}
