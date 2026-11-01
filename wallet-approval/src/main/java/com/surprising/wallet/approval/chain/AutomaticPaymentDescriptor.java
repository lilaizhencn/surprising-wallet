package com.surprising.wallet.approval.chain;

import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.signing.*;
import com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec;
import com.surprising.wallet.sdk.signing.evm7702.*;
import com.surprising.wallet.sdk.signing.hypercore.MpcHyperCoreActionCodec;
import com.surprising.wallet.sdk.signing.xrp.MpcXrpTransactionCodec;
import java.math.BigDecimal;
import java.util.*;
import org.xrpl.xrpl4j.model.transactions.Payment;
import org.web3j.utils.Numeric;
import tools.jackson.databind.*;

/** Decode an operating request for policy selection. This is never sufficient to authorize: the complete wire verifier runs afterwards. */
public record AutomaticPaymentDescriptor(String symbol,String network,List<Transfer> transfers,List<UUID> sourceKeyIds) {
    public AutomaticPaymentDescriptor {
        transfers=List.copyOf(transfers);sourceKeyIds=sourceKeyIds.stream().distinct().sorted().toList();
        if(symbol==null || transfers.isEmpty() || transfers.size()>100 || sourceKeyIds.isEmpty() || sourceKeyIds.size()>1000)
            throw new IllegalArgumentException("bounded typed automatic payment required");
    }
    public record Transfer(String reference,String sender,String recipient,BigDecimal amount) {
        public Transfer {
            if(reference==null || reference.isBlank() || sender==null || sender.isBlank() || recipient==null || recipient.isBlank() || amount==null || amount.signum()<0)
                throw new IllegalArgumentException("fixed automatic transfer facts required");
        }
    }
    public static AutomaticPaymentDescriptor decode(MpcSignCommand command,RegisteredMpcKey signer,ObjectMapper json) {
        if(command.payloadEncoding().equals("NEAR_STORAGE_REGISTRATION_JSON")) {
            var p=payload(command,NearRegistrationPayload.class,json);return one(p.payment().symbol(),p.unsigned().network(),command.businessReference(),p.unsigned().sender(),p.payment().recipient(),p.payment().amount(),List.of(command.keyId()));
        }
        if(command.chain().isBitcoinLike()) {
            var p=UtxoSigningPayload.decode(command,json);var request=p.task().request();
            if(request.operation()!=UtxoSigningRequest.Operation.COLLECTION)throw new IllegalArgumentException("only fixed collection batches use automatic UTXO rules");
            String source=request.inputs().getFirst().address();
            if(request.inputs().stream().anyMatch(i->!i.address().equals(source)))throw new IllegalArgumentException("automatic UTXO collection requires one original owned source");
            return new AutomaticPaymentDescriptor(request.asset().chain(),request.asset().network().name().toLowerCase(Locale.ROOT),request.payments().stream().map(pmt->new Transfer(pmt.reference(),source,pmt.address(),pmt.amount())).toList(),p.keyIds());
        }
        if(command.payloadEncoding().equals(Evm7702SigningPayload.ENCODING)) {
            var envelope=Evm7702SigningPayload.decode(command,json);var p=json.readerFor(Evm7702CollectionPayload.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).<Evm7702CollectionPayload>readValue(envelope.plan().preparationJson());
            if(p.items().size()!=envelope.plan().references().size())throw new IllegalArgumentException("whole automatic collection plan differs");
            var items=new ArrayList<Transfer>();var sources=new ArrayList<UUID>();
            String symbol=null;
            // Symbol and decimal units are provided by the installed rule after exact token/atomic verification.
            for(int i=0;i<p.items().size();i++) {
                var item=p.items().get(i);sources.add(item.authority().getMpcKeyId());
                items.add(new Transfer(envelope.plan().references().get(i),item.request().authority(),item.request().recipient(),new BigDecimal(item.request().amount())));
            }
            return new AutomaticPaymentDescriptor("EIP7702_ATOMIC",null,items,sources);
        }
        List<UUID> source=List.of(command.keyId());String reference=command.businessReference();
        if(command.chain().isEvm()) {
            var p=payload(command,EvmSigningPayload.class,json);var raw=p.raw();String recipient=raw.getTo();
            if(p.contract()!=null) {
                String data=Numeric.cleanHexPrefix(raw.getData());if(!data.matches("a9059cbb[0-9a-fA-F]{128}"))throw new IllegalArgumentException("only canonical ERC-20 automatic transfers are permitted");
                recipient="0x"+data.substring(32,72);
            }
            String sender=evmAddress(signer);return one(p.symbol(),null,reference,sender,recipient,p.amount(),source);
        }
        return switch(command.chain()) {
            case APTOS -> {var p=payload(command,AptosSigningPayload.class,json);var u=p.unsigned();yield one(p.symbol(),null,reference,u.sender(),u.recipient(),p.amount(),source);}
            case SOLANA -> {var p=payload(command,SolanaSigningPayload.class,json);yield one(p.symbol(),null,reference,p.sender(),p.recipient(),p.amount(),source);}
            case TON -> {var p=payload(command,TonSigningPayload.class,json);var u=p.unsigned();yield one(p.symbol(),u.network(),reference,u.sender(),u.recipient(),p.amount(),source);}
            case TRON -> {var p=payload(command,TronSigningPayload.class,json);var u=p.unsigned();yield one(p.symbol(),null,reference,u.owner(),u.recipient(),p.amount(),source);}
            case XRP -> {var p=payload(command,XrpSigningPayload.class,json);var tx=MpcXrpTransactionCodec.restore(p.unsigned());if(command.businessType()==MpcSignCommand.BusinessType.ADDRESS_PREPARATION)yield one(p.symbol(),null,reference,tx.account().value(),tx instanceof Payment payment?payment.destination().value():tx.account().value(),BigDecimal.ZERO,source);if(!(tx instanceof Payment payment))throw new IllegalArgumentException("XRPL preparation requires its explicit operating validator");yield one(p.symbol(),null,reference,tx.account().value(),payment.destination().value(),p.amount(),source);}
            case SUI -> {var p=payload(command,SuiSigningPayload.class,json);var u=p.unsigned();yield one(p.symbol(),u.network(),reference,u.sender(),u.recipient(),p.amount(),source);}
            case ADA -> {var p=payload(command,CardanoSigningPayload.class,json);var u=p.unsigned();yield one(p.symbol(),null,reference,u.sender(),u.recipient(),p.amount(),source);}
            case DOT -> {var p=payload(command,PolkadotSigningPayload.class,json);var u=p.unsigned();yield one(p.symbol(),u.network(),reference,u.sender(),u.recipient(),p.amount(),source);}
            case NEAR -> {var p=payload(command,NearSigningPayload.class,json);var u=p.unsigned();yield one(p.symbol(),u.network(),reference,u.sender(),u.recipient(),p.amount(),source);}
            case HYPERCORE -> {var p=payload(command,HyperCoreSigningPayload.class,json);var u=p.unsigned();var a=MpcHyperCoreActionCodec.restore(u);yield one(p.symbol(),a.path("hyperliquidChain").asText().equals("Mainnet")?"mainnet":"testnet",reference,u.owner(),a.path("destination").asText(),p.amount(),source);}
            case XMR -> {var p=payload(command,MoneroSigningPayload.class,json);yield one("XMR",p.network(),reference,p.sender(),p.recipient(),p.amount(),source);}
            default -> throw new IllegalArgumentException("typed automatic transaction decoder required");
        };
    }
    /** Solana's current slot may be the payer. Resolve the payment source from the independent registry before returning this descriptor. */
    public AutomaticPaymentDescriptor withSources(List<UUID> ids){return new AutomaticPaymentDescriptor(symbol,network,transfers,ids);}
    private static AutomaticPaymentDescriptor one(String symbol,String network,String reference,String sender,String recipient,BigDecimal amount,List<UUID> source) {
        return new AutomaticPaymentDescriptor(symbol,network,List.of(new Transfer(reference,sender,recipient,amount)),source);
    }
    public static <T> T payload(MpcSignCommand command,Class<T> type,ObjectMapper json) {
        return json.readerFor(type).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readValue(HexFormat.of().parseHex(command.payloadHex()));
    }
    private static String evmAddress(RegisteredMpcKey key) {
        return "0x"+org.web3j.crypto.Keys.getAddress(org.web3j.crypto.Sign.publicFromPoint(org.bitcoinj.crypto.ECKey.fromPublicOnly(HexFormat.of().parseHex(key.publicKeyHex())).decompress().getPubKey()));
    }
}
