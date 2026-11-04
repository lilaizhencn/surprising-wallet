package com.surprising.wallet.approval.gateway;

import com.surprising.wallet.approval.chain.RegisteredMpcAddresses;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.common.approval.ApprovalAssetSnapshot;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.sdk.signing.cardano.MpcCardanoTransactionCodec;
import com.surprising.wallet.sdk.signing.solana.MpcSolanaTransactionCodec;
import com.surprising.wallet.sdk.signing.sui.MpcSuiTransactionCodec;
import com.surprising.wallet.sdk.signing.ton.MpcTonTransactionCodec;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.web3j.utils.Numeric;
import org.web3j.crypto.Hash;
import org.ton.ton4j.address.Address;
import org.ton.ton4j.cell.*;
import sui.rpc.v2.*;
import utxorpc.v1beta.cardano.Cardano;
import utxorpc.v1beta.query.*;
import tools.jackson.databind.*;

/** Reads actual owned principal before automatic admission/preparation. These observations are never accepted from Wallet JSON. */
@Component
public class ApprovalOperatingBalanceReader {
    private final ApprovalChainRpcClient rpc;private final ApprovalNativeRegistryClient holders;private final ObjectMapper json;private final Clock clock;
    public ApprovalOperatingBalanceReader(ApprovalChainRpcClient rpc,ApprovalNativeRegistryClient holders,ObjectMapper json,Clock clock){this.rpc=rpc;this.holders=holders;this.json=json;this.clock=clock;}
    public Balance observe(ApprovalAssetSnapshot asset,RegisteredMpcKey key,String owner,MpcClient.Request originalMoneroObservation) {
        return observeVerified(asset,key,owner,originalMoneroObservation,false);
    }
    /** Incident read only: disabled keys can be observed, never admitted for payment. */
    public Balance observeQuarantined(ApprovalAssetSnapshot asset,RegisteredMpcKey key,String owner) {
        require(key!=null && key.state().equals("DISABLED"),"quarantined balance requires a disabled key");
        return observeVerified(asset,key,owner,null,true);
    }
    /** Recheck the live code of every EVM signing account, including manually approved payments and replay sessions. */
    public void requireEvmSigningCode(ApprovalAssetSnapshot asset,RegisteredMpcKey key) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),
                "EVM signing account code observation requires no database transaction");
        require(ChainType.valueOf(asset.chain()).isEvm() && key!=null && key.state().equals("ACTIVE"),
                "active independently registered EVM signing key required");
        String owner=RegisteredMpcAddresses.address(key,asset);
        var reads=rpc.open(asset,"DEFAULT");
        require(asset.networkIdentity().equals("eip155:"+quantity(reads.rpc("eth_chainId",List.of()))),
                "signing EVM node chain ID differs");
        var block=reads.rpc("eth_getBlockByNumber",List.of("latest",false));
        String number=block.path("number").asText(),hash=block.path("hash").asText();
        require(hash.matches("0x[0-9a-fA-F]{64}") && quantity(block.path("number")).bitLength()<=63,
                "canonical EVM signing block required");
        evmCode(reads,key,owner,number,false);
        var confirmed=reads.rpc("eth_getBlockByNumber",List.of(number,false));
        require(number.equals(confirmed.path("number").asText())
                && hash.equalsIgnoreCase(confirmed.path("hash").asText()),
                "EVM signing code observation crossed a chain reorganization");
    }
    /** A direct EVM payment needs a finalized exact receipt and an anchored current balance. */
    public FinalizedSpend observeFinalizedDirectSpend(ApprovalAssetSnapshot asset,RegisteredMpcKey key,
            String owner,String recipient,String txHash,BigInteger amountAtomic) {
        require(ChainType.valueOf(asset.chain()).isEvm() && RegisteredMpcAddresses.matches(key,asset,owner),
                "active independently owned EVM spend required");
        return observeFinalizedDirectPayment(asset,owner,recipient,txHash,amountAtomic,key,owner);
    }
    /** A direct collection proves the source payment and samples the receiving hot wallet after finality. */
    public FinalizedSpend observeFinalizedDirectCollection(ApprovalAssetSnapshot asset,
            RegisteredMpcKey sourceKey,String source,RegisteredMpcKey destinationKey,
            String destination,String txHash,BigInteger amountAtomic) {
        require(ChainType.valueOf(asset.chain()).isEvm()
                && RegisteredMpcAddresses.matchesIdentity(sourceKey,asset,source)
                && RegisteredMpcAddresses.matches(destinationKey,asset,destination)
                && !source.equalsIgnoreCase(destination),
                "independently owned EVM collection source and active target required");
        return observeFinalizedDirectPayment(asset,source,destination,txHash,amountAtomic,
                destinationKey,destination);
    }
    private FinalizedSpend observeFinalizedDirectPayment(ApprovalAssetSnapshot asset,String owner,
            String recipient,String txHash,BigInteger amountAtomic,RegisteredMpcKey balanceKey,
            String balanceOwner) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),
                "finalized direct payment observation requires no database transaction");
        require(ChainType.valueOf(asset.chain()).isEvm()
                && owner!=null && owner.matches("0x[0-9a-fA-F]{40}")
                && recipient!=null && recipient.matches("0x[0-9a-fA-F]{40}")
                && !recipient.equalsIgnoreCase(owner) && txHash!=null
                && txHash.matches("0x[0-9a-fA-F]{64}") && amountAtomic!=null
                && amountAtomic.signum()>0 && amountAtomic.bitLength()<=256,
                "complete independently owned direct EVM spend required");
        var reads=rpc.open(asset,"DEFAULT");
        require(asset.networkIdentity().equals("eip155:"+quantity(reads.rpc("eth_chainId",List.of()))),
                "finalized EVM node chain ID differs");
        var finalBlock=reads.rpc("eth_getBlockByNumber",List.of("finalized",false));
        require(finalBlock.isObject() && finalBlock.path("hash").asText().matches("0x[0-9a-fA-F]{64}"),
                "independent finalized EVM block is unavailable");
        BigInteger finalHeight=quantity(finalBlock.path("number"));
        require(finalHeight.bitLength()<=63,"finalized EVM height is out of range");
        String finalNumber=finalBlock.path("number").asText();
        String finalHash=finalBlock.path("hash").asText().toLowerCase(Locale.ROOT);
        var receipt=reads.rpc("eth_getTransactionReceipt",List.of(txHash));
        var transaction=reads.rpc("eth_getTransactionByHash",List.of(txHash));
        require(receipt.isObject() && transaction.isObject()
                && txHash.equalsIgnoreCase(receipt.path("transactionHash").asText())
                && txHash.equalsIgnoreCase(transaction.path("hash").asText())
                && "0x1".equalsIgnoreCase(receipt.path("status").asText())
                && owner.equalsIgnoreCase(receipt.path("from").asText())
                && owner.equalsIgnoreCase(transaction.path("from").asText())
                && receipt.path("blockHash").asText().matches("0x[0-9a-fA-F]{64}")
                && receipt.path("blockHash").asText().equalsIgnoreCase(transaction.path("blockHash").asText())
                && receipt.path("blockNumber").asText().equals(transaction.path("blockNumber").asText()),
                "finalized EVM payment receipt or sender identity differs");
        BigInteger receiptHeight=quantity(receipt.path("blockNumber"));
        require(receiptHeight.bitLength()<=63 && receiptHeight.compareTo(finalHeight)<=0,
                "EVM payment receipt is beyond the finalized anchor");
        var receiptBlock=reads.rpc("eth_getBlockByNumber",List.of(receipt.path("blockNumber").asText(),false));
        require(receipt.path("blockHash").asText().equalsIgnoreCase(receiptBlock.path("hash").asText())
                && receipt.path("blockNumber").asText().equals(receiptBlock.path("number").asText()),
                "EVM payment receipt is not on the canonical finalized chain");
        if(asset.tokenIdentifier()==null) {
            require(recipient.equalsIgnoreCase(receipt.path("to").asText())
                    && recipient.equalsIgnoreCase(transaction.path("to").asText())
                    && amountAtomic.equals(quantity(transaction.path("value")))
                    && "0x".equalsIgnoreCase(transaction.path("input").asText()),
                    "finalized native payment differs from the original withdrawal");
        } else {
            String contract=asset.tokenIdentifier();
            String expectedInput="0xa9059cbb"+"0".repeat(24)
                    +recipient.substring(2)+String.format("%064x",amountAtomic);
            require(contract.equalsIgnoreCase(receipt.path("to").asText())
                    && contract.equalsIgnoreCase(transaction.path("to").asText())
                    && quantity(transaction.path("value")).signum()==0
                    && expectedInput.equalsIgnoreCase(transaction.path("input").asText())
                    && directTransferLogMatches(receipt,contract,owner,recipient,amountAtomic),
                    "finalized token payment differs from the original withdrawal");
        }
        var latestBlock=reads.rpc("eth_getBlockByNumber",List.of("latest",false));
        require(latestBlock.isObject() && latestBlock.path("hash").asText().matches("0x[0-9a-fA-F]{64}"),
                "independent latest EVM balance block is unavailable");
        BigInteger latestHeight=quantity(latestBlock.path("number"));
        require(latestHeight.bitLength()<=63 && latestHeight.compareTo(finalHeight)>=0,
                "latest EVM balance is behind the finalized receipt");
        String latestNumber=latestBlock.path("number").asText();
        String latestHash=latestBlock.path("hash").asText().toLowerCase(Locale.ROOT);
        evmCode(reads,balanceKey,balanceOwner,latestNumber,false);
        BigInteger nativeAmount=quantity(reads.rpc("eth_getBalance",List.of(balanceOwner,latestNumber)));
        BigInteger assetAmount=asset.tokenIdentifier()==null?nativeAmount
                :quantity(reads.rpc("eth_call",List.of(Map.of("to",asset.tokenIdentifier(),
                        "data","0x70a08231"+"0".repeat(24)+balanceOwner.substring(2)),latestNumber)));
        var latestAgain=reads.rpc("eth_getBlockByNumber",List.of(latestNumber,false));
        require(latestHash.equalsIgnoreCase(latestAgain.path("hash").asText())
                && latestNumber.equals(latestAgain.path("number").asText()),
                "latest EVM balance anchor changed during observation");
        var finalAgain=reads.rpc("eth_getBlockByNumber",List.of(finalNumber,false));
        require(finalHash.equalsIgnoreCase(finalAgain.path("hash").asText())
                && finalNumber.equals(finalAgain.path("number").asText()),
                "finalized EVM receipt anchor changed during balance observation");
        return new FinalizedSpend(owner,recipient,txHash.toLowerCase(Locale.ROOT),amountAtomic,
                assetAmount,nativeAmount,receiptHeight.longValueExact(),
                receipt.path("blockHash").asText().toLowerCase(Locale.ROOT),
                latestHeight.longValueExact(),latestHash,clock.instant());
    }
    private static boolean directTransferLogMatches(JsonNode receipt,String contract,String source,
            String recipient,BigInteger amountAtomic) {
        var logs=receipt.path("logs");
        if(!logs.isArray() || logs.size()>1000)return false;
        String event="0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
        String fromTopic="0x"+"0".repeat(24)+source.substring(2);
        String toTopic="0x"+"0".repeat(24)+recipient.substring(2);
        int matches=0;
        for(var log:logs) {
            var topics=log.path("topics");
            if(!contract.equalsIgnoreCase(log.path("address").asText()) || !topics.isArray()
                    || topics.size()!=3 || !event.equalsIgnoreCase(topics.path(0).asText())
                    || !fromTopic.equalsIgnoreCase(topics.path(1).asText()))continue;
            if(log.path("removed").asBoolean()
                    || !receipt.path("transactionHash").asText().equalsIgnoreCase(log.path("transactionHash").asText())
                    || !receipt.path("blockHash").asText().equalsIgnoreCase(log.path("blockHash").asText())
                    || !receipt.path("blockNumber").asText().equals(log.path("blockNumber").asText())
                    || !toTopic.equalsIgnoreCase(topics.path(2).asText())
                    || !amountAtomic.equals(quantity(log.path("data"))))return false;
            matches++;
        }
        return matches==1;
    }
    public record FinalizedSpend(String sourceAddress,String recipient,String txHash,BigInteger amountAtomic,
            BigInteger availableAtomic,BigInteger nativeAtomic,long receiptBlockHeight,
            String receiptBlockHash,long observedBlockHeight,String observedBlockHash,Instant observedAt) { }
    private Balance observeVerified(ApprovalAssetSnapshot asset,RegisteredMpcKey key,String owner,
                                    MpcClient.Request originalMoneroObservation,boolean quarantinedRead) {
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"independent balance queries require no database transaction");
        require(quarantinedRead?RegisteredMpcAddresses.matchesIdentity(key,asset,owner)
                        :RegisteredMpcAddresses.matches(key,asset,owner),
                "operating balance owner differs from the independently enrolled key");
        Instant started=clock.instant();var chain=ChainType.valueOf(asset.chain());Amounts amounts;
        if(chain.isEvm())amounts=evm(asset,key,owner,quarantinedRead);
        else amounts=switch(chain) {
            case ADA -> cardano(asset,owner);
            case SUI -> sui(asset,owner);
            case DOT -> polkadot(asset,owner);
            case NEAR -> near(asset,owner);
            case XMR -> monero(asset,key,owner,originalMoneroObservation);
            case APTOS -> aptos(asset,owner);
            case SOLANA -> solana(asset,owner);
            case TON -> ton(asset,owner);
            case TRON -> tron(asset,owner);
            case XRP -> xrp(asset,owner);
            case HYPERCORE -> hyperCore(asset,owner);
            default -> throw new IllegalArgumentException("UTXO operating balance requires the actual fixed live input set");
        };
        return new Balance(asset.chain(),asset.networkIdentity(),asset.symbol(),asset.decimals(),asset.nativeDecimals(),key.keyId(),owner,amounts.available(),amounts.nativeAmount(),started,
                amounts.accountCodeState(),amounts.delegatedTo(),amounts.observedBlockHash(),amounts.observedBlockHeight());
    }
    private Amounts evm(ApprovalAssetSnapshot asset,RegisteredMpcKey key,String owner,boolean incident) {
        var reads=rpc.open(asset,"DEFAULT");require(asset.networkIdentity().equals("eip155:"+quantity(reads.rpc("eth_chainId",List.of()))),"operating EVM node chain ID differs");
        var block=reads.rpc("eth_getBlockByNumber",List.of("latest",false));require(block.path("hash").asText().matches("0x[0-9a-f]{64}") && block.path("number").isString(),"operating EVM canonical tip missing");String number=block.path("number").asText();
        BigInteger nativeAmount=quantity(reads.rpc("eth_getBalance",List.of(owner,number))),amount=nativeAmount;
        if(asset.tokenIdentifier()!=null)amount=quantity(reads.rpc("eth_call",List.of(Map.of("to",asset.tokenIdentifier(),"data","0x70a08231"+"0".repeat(24)+owner.substring(2)),number)));
        var code=evmCode(reads,key,owner,number,incident);
        var confirmed=reads.rpc("eth_getBlockByNumber",List.of(number,false));
        require(block.path("hash").asText().equalsIgnoreCase(confirmed.path("hash").asText())
                && number.equals(confirmed.path("number").asText()),
                "independent EVM balance observation crossed a chain reorganization");
        BigInteger height=quantity(block.path("number"));
        require(height.bitLength()<=63,"independent EVM block height is out of range");
        String hash=block.path("hash").asText().toLowerCase(Locale.ROOT);
        return new Amounts(amount,nativeAmount,code.state(),code.delegatedTo(),hash,height.longValueExact());
    }
    private static EvmCode evmCode(ApprovalChainRpcClient.Reads reads,RegisteredMpcKey key,
            String owner,String blockNumber,boolean incident) {
        String raw=reads.rpc("eth_getCode",List.of(owner,blockNumber)).asText();
        require(raw.matches("0x(?:[0-9a-fA-F]{2}){0,65536}"),
                "independent EVM account code is malformed or oversized");
        if(raw.equals("0x"))return new EvmCode("EMPTY",null);
        if(!raw.matches("(?i)0xef0100[0-9a-f]{40}")) {
            require(incident,"hot wallet account has unrecognized executable code");
            return new EvmCode("UNRECOGNIZED_CODE",null);
        }
        String delegate="0x"+raw.substring(8).toLowerCase(Locale.ROOT);
        if(!incident) {
            boolean approved=false;
            for(String purpose:List.of("payoutDelegate","collectionDelegate")) {
                String installed=reads.optionalParameter(purpose+"."+key.tenantId());
                if(installed==null || !delegate.equalsIgnoreCase(installed))continue;
                String expectedHash=reads.optionalParameter(purpose+"CodeHash."+key.tenantId());
                require(expectedHash!=null && expectedHash.matches("(?i)0x[0-9a-f]{64}"),
                        "installed hot wallet delegate code hash is missing");
                String delegateCode=reads.rpc("eth_getCode",List.of(delegate,blockNumber)).asText();
                require(delegateCode.matches("0x(?:[0-9a-fA-F]{2}){1,65536}")
                        && expectedHash.equalsIgnoreCase(Hash.sha3(delegateCode)),
                        "hot wallet delegate bytecode differs from independent policy");
                approved=true;break;
            }
            require(approved,"hot wallet delegates to an unapproved EIP-7702 implementation");
        }
        return new EvmCode("EIP7702_DELEGATED",delegate);
    }
    private record EvmCode(String state,String delegatedTo) { }
    private Amounts aptos(ApprovalAssetSnapshot asset,String owner) {
        var reads=rpc.open(asset,"DEFAULT");var ledger=reads.ledger();require(asset.networkIdentity().equals("aptos:"+integer(ledger.path("chain_id"))),"operating Aptos REST chain ID differs");
        var nativeResult=reads.rest("/view",Map.of("function","0x1::coin::balance","type_arguments",List.of("0x1::aptos_coin::AptosCoin"),"arguments",List.of(owner)));
        require(nativeResult.isArray() && nativeResult.size()==1,"independent APT balance result differs");BigInteger nativeAmount=integer(nativeResult.path(0)),amount=nativeAmount;
        if(asset.tokenIdentifier()!=null) {
            var result=reads.rest("/view",Map.of("function","0x1::primary_fungible_store::balance","type_arguments",List.of("0x1::fungible_asset::Metadata"),"arguments",List.of(owner,asset.tokenIdentifier())));
            require(result.isArray() && result.size()==1,"independent primary FA balance result differs");amount=integer(result.path(0));
        }
        return new Amounts(amount,nativeAmount);
    }
    private Amounts solana(ApprovalAssetSnapshot asset,String owner) {
        var reads=rpc.open(asset,"DEFAULT");require(asset.networkIdentity().equals("solana:"+reads.rpc("getGenesisHash",List.of()).asText()),"operating Solana genesis differs");
        BigInteger nativeAmount=integer(reads.rpc("getBalance",List.of(owner,Map.of("commitment","confirmed"))).path("value")),amount=nativeAmount;
        if(asset.tokenIdentifier()!=null) {
            var token=reads.rpc("getTokenAccountBalance",List.of(MpcSolanaTransactionCodec.associatedTokenAddress(owner,asset.tokenIdentifier()),Map.of("commitment","confirmed"))).path("value");
            require(integer(token.path("decimals")).intValueExact()==asset.decimals(),"independent SPL precision differs");amount=integer(token.path("amount"));
        }
        return new Amounts(amount,nativeAmount);
    }
    private Amounts tron(ApprovalAssetSnapshot asset,String owner) {
        var reads=rpc.open(asset,"DEFAULT");require(asset.networkIdentity().equals("tron:"+reads.rest("/wallet/getblockbynum",Map.of("num",0)).path("blockID").asText()),"operating TRON genesis differs");
        var account=reads.rest("/wallet/getaccount",Map.of("address",owner,"visible",true));BigInteger nativeAmount=account.has("balance")?integer(account.path("balance")):BigInteger.ZERO,amount=nativeAmount;
        if(asset.tokenIdentifier()!=null) {
            String parameter="0".repeat(24)+com.surprising.wallet.sdk.signing.tron.TronAddressCodec.base58ToHex(owner).substring(2);
            var result=reads.rest("/wallet/triggerconstantcontract",Map.of("owner_address",owner,"contract_address",asset.tokenIdentifier(),"function_selector","balanceOf(address)","parameter",parameter,"visible",true));
            require(result.path("result").path("result").isBoolean() && result.path("result").path("result").asBoolean()
                    && result.path("constant_result").isArray() && result.path("constant_result").size()==1 && result.path("constant_result").path(0).asText().matches("[0-9a-fA-F]{64}"),"independent TRC-20 balance call failed");amount=new BigInteger(result.path("constant_result").path(0).asText(),16);
        }
        return new Amounts(amount,nativeAmount);
    }
    private Amounts xrp(ApprovalAssetSnapshot asset,String owner) {
        var reads=rpc.open(asset,"DEFAULT");var server=reads.rpc("server_info",List.of()).path("info");require(asset.networkIdentity().equals("xrpl:"+server.path("network_id").asText()),"operating XRPL network differs");
        var account=reads.rpc("account_info",List.of(Map.of("account",owner,"ledger_index","validated","strict",true))).path("account_data");require(account.path("Account").asText().equals(owner),"XRPL owned balance account differs");
        BigInteger reserve=decimalAtomic(server.path("validated_ledger").path("reserve_base_xrp"),6)
                .add(decimalAtomic(server.path("validated_ledger").path("reserve_inc_xrp"),6).multiply(integer(account.path("OwnerCount"))));
        BigInteger nativeAmount=integer(account.path("Balance")).subtract(reserve).max(BigInteger.ZERO),amount=nativeAmount;
        if(asset.tokenIdentifier()!=null) {
            String[] token=asset.tokenIdentifier().split(":",-1);var lines=reads.rpc("account_lines",List.of(Map.of("account",owner,"peer",token[0],"ledger_index","validated","limit",400)));
            require(lines.path("lines").isArray() && !lines.hasNonNull("marker"),"independent XRPL trust-line page is incomplete");amount=BigInteger.ZERO;boolean found=false;
            for(var line:lines.path("lines"))if(line.path("account").asText().equals(token[0]) && line.path("currency").asText().equalsIgnoreCase(token[1])) {
                require(!found && !line.path("freeze").asBoolean() && !line.path("freeze_peer").asBoolean(),"XRPL trust line is duplicated or frozen");found=true;amount=decimalAtomic(line.path("balance"),asset.decimals());
            }
        }
        return new Amounts(amount,nativeAmount);
    }
    private Amounts ton(ApprovalAssetSnapshot asset,String owner) {
        var reads=rpc.open(asset,"DEFAULT");var info=reads.rpc("getMasterchainInfo",Map.of());String root=info.path("init").path("root_hash").asText();
        String hash=root.matches("[0-9a-f]{64}")?root:HexFormat.of().formatHex(Base64.getDecoder().decode(root));require(asset.networkIdentity().equals("ton:"+hash),"operating TON zero-state differs");
        BigInteger nativeAmount=integer(reads.rpc("getAddressBalance",Map.of("address",owner))),amount=nativeAmount;
        if(asset.tokenIdentifier()!=null) {
            var slice=CellBuilder.beginCell().storeAddress(Address.of(owner)).endCell();var result=reads.rpc("runGetMethod",Map.of("address",asset.tokenIdentifier(),"method","get_wallet_address","stack",List.of(List.of("tvm.Slice",Base64.getEncoder().encodeToString(slice.toBoc(false))))));
            getter(result,1);String wallet=stackAddress(result.path("stack").path(0));var data=reads.rpc("runGetMethod",Map.of("address",wallet,"method","get_wallet_data","stack",List.of()));getter(data,4);
            require(stackAddress(data.path("stack").path(1)).equals(MpcTonTransactionCodec.canonicalAddress(owner)) && stackAddress(data.path("stack").path(2)).equals(MpcTonTransactionCodec.canonicalAddress(asset.tokenIdentifier())),"independent Jetton wallet owner/master differs");
            var value=data.path("stack").path(0);require(value.path(0).asText().equals("num"),"independent Jetton balance is not an integer");amount=quantity(value.path(1));
        }
        return new Amounts(amount,nativeAmount);
    }
    private Amounts near(ApprovalAssetSnapshot asset,String owner) {
        var reads=rpc.open(asset,"DEFAULT");require(asset.networkIdentity().equals("near:"+reads.rpc("block",Map.of("block_id",0)).path("header").path("hash").asText()),"operating NEAR genesis differs");
        String block=reads.rpc("block",Map.of("finality","final")).path("header").path("hash").asText();require(!block.isBlank(),"NEAR final reference missing");
        var account=reads.rpc("query",Map.of("request_type","view_account","block_id",block,"account_id",owner));var protocol=reads.rpc("EXPERIMENTAL_protocol_config",Map.of("block_id",block));
        BigInteger storage=integer(account.path("storage_usage")).multiply(integer(protocol.path("runtime_config").path("storage_amount_per_byte")));
        BigInteger nativeAmount=integer(account.path("amount")).subtract(storage).max(BigInteger.ZERO),amount=nativeAmount;
        if(asset.tokenIdentifier()!=null) {
            var response=reads.rpc("query",Map.of("request_type","call_function","block_id",block,"account_id",asset.tokenIdentifier(),"method_name","ft_balance_of",
                    "args_base64",Base64.getEncoder().encodeToString(json.writeValueAsBytes(Map.of("account_id",owner)))));amount=integer(viewResult(response));
        }
        return new Amounts(amount,nativeAmount);
    }
    private Amounts polkadot(ApprovalAssetSnapshot asset,String owner) {
        var reads=rpc.open(asset,"ASSET_HUB");var body=new LinkedHashMap<String,Object>();body.put("chain","DOT");body.put("rpcUrl",reads.parameter("rpcUrl"));body.put("address",owner);if(asset.tokenIdentifier()!=null)body.put("assetId",asset.tokenIdentifier());
        var info=runtime(reads.rest("/v1/polkadot/network-info",body));require(asset.networkIdentity().equals("polkadot:"+info.path("genesisHash").asText().replaceFirst("^0x",""))
                && integer(info.path("nativeDecimals")).intValueExact()==asset.nativeDecimals() && integer(info.path("decimals")).intValueExact()==asset.decimals(),"operating DOT network/precision differs");
        BigInteger nativeAmount=integer(info.path("nativeBalance")).subtract(integer(info.path("frozenBalance"))).max(BigInteger.ZERO),amount=nativeAmount;
        if(asset.tokenIdentifier()!=null) {
            var balance=runtime(reads.rest("/v1/polkadot/asset-balance",body));require(balance.path("assetId").asText().equals(asset.tokenIdentifier()) && Set.of("Liquid","NONE").contains(balance.path("status").asText()),"independent Asset Hub balance is frozen or mismatched");amount=integer(balance.path("balance"));
        }
        return new Amounts(amount,nativeAmount);
    }
    private Amounts sui(ApprovalAssetSnapshot asset,String owner) {
        try(var reads=rpc.grpc(asset)) {
            var ledger=LedgerServiceGrpc.newBlockingStub(reads.channel()).withDeadline(reads.deadline());var info=ledger.getServiceInfo(LedgerServiceOuterClass.GetServiceInfoRequest.getDefaultInstance());require(info.hasChainId() && asset.networkIdentity().equals("sui:"+info.getChainId()),"operating Sui genesis differs");
            var state=StateServiceGrpc.newBlockingStub(reads.channel()).withDeadline(reads.deadline());String nativeType="0x2::sui::SUI";
            BigInteger nativeAmount=suiBalance(state,owner,nativeType),amount=asset.tokenIdentifier()==null?nativeAmount:suiBalance(state,owner,asset.tokenIdentifier());return new Amounts(amount,nativeAmount);
        }
    }
    private static BigInteger suiBalance(StateServiceGrpc.StateServiceBlockingStub state,String owner,String coinType) {
        var result=state.getBalance(StateServiceOuterClass.GetBalanceRequest.newBuilder().setOwner(MpcSuiTransactionCodec.canonicalAddress(owner)).setCoinType(coinType).build());require(result.hasBalance(),"independent Sui balance missing");return new BigInteger(Long.toUnsignedString(result.getBalance().getBalance()));
    }
    private Amounts cardano(ApprovalAssetSnapshot asset,String owner) {
        try(var reads=rpc.grpc(asset)) {
            var query=QueryServiceGrpc.newBlockingStub(reads.channel()).withDeadline(reads.deadline());var genesis=query.readGenesis(Query.ReadGenesisRequest.getDefaultInstance());
            require(genesis.hasCardano() && genesis.getGenesis().size()==32 && asset.networkIdentity().equals("cardano:"+HexFormat.of().formatHex(genesis.getGenesis().toByteArray())+":"+Integer.toUnsignedLong(genesis.getCardano().getNetworkMagic())),"operating Cardano genesis/magic differs");
            byte[] address=MpcCardanoTransactionCodec.addressBytes(owner);var pattern=Query.UtxoPredicate.newBuilder().setMatch(Query.AnyUtxoPattern.newBuilder().setCardano(Cardano.TxOutputPattern.newBuilder().setAddress(Cardano.AddressPattern.newBuilder().setExactAddress(ByteString.copyFrom(address))))).build();
            String token="";Query.ChainPoint snapshot=null;var pages=new HashSet<String>();var refs=new HashSet<Query.TxoRef>();BigInteger nativeAmount=BigInteger.ZERO,amount=BigInteger.ZERO;
            do {
                var request=Query.SearchUtxosRequest.newBuilder().setPredicate(pattern).setMaxItems(500);if(!token.isEmpty())request.setStartToken(token);var page=query.searchUtxos(request.build());
                require(page.hasLedgerTip() && page.getLedgerTip().getHash().size()==32 && (snapshot==null || snapshot.equals(page.getLedgerTip())),"independent Cardano balance pagination crossed snapshots");snapshot=page.getLedgerTip();
                for(var item:page.getItemsList()) {
                    require(item.hasCardano() && item.getTxoRef().getHash().size()==32 && refs.add(item.getTxoRef()) && refs.size()<=10000
                            && item.getCardano().getAddress().equals(ByteString.copyFrom(address)),"Cardano balance includes a duplicate/foreign output or exceeds bounds");var output=item.getCardano();
                    require(!output.hasDatum() && (!output.hasScript() || output.getScript().equals(Cardano.Script.getDefaultInstance())),"operating Cardano input requires scripts");nativeAmount=nativeAmount.add(cardanoInteger(output.getCoin()));
                    if(asset.tokenIdentifier()!=null)for(var policy:output.getAssetsList())for(var coin:policy.getAssetsList()) {
                        String unit=HexFormat.of().formatHex(policy.getPolicyId().toByteArray())+HexFormat.of().formatHex(coin.getName().toByteArray());if(unit.equals(asset.tokenIdentifier()))amount=amount.add(cardanoInteger(coin.getQuantity()));
                    }
                }
                token=page.getNextToken();require(token.isEmpty() || pages.add(token),"independent Cardano balance repeated a page");
            } while(!token.isEmpty());
            return new Amounts(asset.tokenIdentifier()==null?nativeAmount:amount,nativeAmount);
        }
    }
    private Amounts monero(ApprovalAssetSnapshot asset,RegisteredMpcKey key,String owner,MpcClient.Request request) {
        require(request!=null && request.tenantId().equals(key.tenantId()) && request.keyId().equals(key.keyId()) && request.epoch()==key.epoch(),"original native Monero source observation required");
        var policy=json.readTree(HexFormat.of().parseHex(request.messageHex()));require(policy.path("kind").asText().equals("SNAPSHOT") && policy.path("network").asText().equals(asset.network())
                && asset.networkIdentity().equals("monero:"+policy.path("genesisHash").asText()) && policy.path("sender").asText().equals(owner) && !policy.has("transactionHash"),"Monero observation network/source/purpose differs");
        var reads=rpc.open(asset,"DEFAULT");var info=reads.rpc("get_info",Map.of());require(info.path("status").asText().equals("OK") && !info.path("untrusted").asBoolean()
                && info.path("nettype").asText().equals(asset.network().equals("regtest")?"fakechain":asset.network()) && (!asset.network().equals("regtest") || info.path("offline").asBoolean()),"independent Monero daemon is on another network or untrusted");
        var genesis=reads.rpc("get_block_header_by_height",Map.of("height",0)).path("block_header");require(asset.networkIdentity().equals("monero:"+genesis.path("hash").asText()),"independent Monero genesis differs");
        long height=integer(policy.path("height")).longValueExact(),tip=integer(info.path("height")).longValueExact()-1;require(height<=tip && height>=Math.max(0,tip-10),"original Monero balance observation is stale or ahead of the independent node");
        var header=reads.rpc("get_block_header_by_height",Map.of("height",height)).path("block_header");require(header.path("height").isIntegralNumber() && header.path("height").asLong()==height
                && header.path("orphan_status").isBoolean() && !header.path("orphan_status").asBoolean() && header.path("hash").asText().equals(policy.path("blockHash").asText()),"Monero balance observation is not on the independently observed canonical chain");
        var actual=holders.moneroSnapshot(request);require(actual.publicKeyHex().equals(key.publicKeyHex()) && actual.publicAddress().equals(owner),"Monero holders observed another registered source");
        var unlocked=integer(actual.monero().path("unlockedBalanceAtomic"));return new Amounts(unlocked,unlocked);
    }
    private Amounts hyperCore(ApprovalAssetSnapshot asset,String owner) {
        var reads=rpc.open(asset,"DEFAULT");require(reads.parameter("hyperliquidChain").equals(asset.network().equals("mainnet")?"Mainnet":"Testnet"),"independent HyperCore endpoint network differs");
        var metadata=reads.rest("/info",Map.of("type","spotMeta"));var state=reads.rest("/info",Map.of("type","spotClearinghouseState","user",owner));
        require(metadata.path("tokens").isArray() && state.path("balances").isArray() && state.path("balances").size()<=10000,"independent HyperCore spot state malformed");Integer index=null;
        for(var token:metadata.path("tokens"))if((token.path("name").asText()+":"+token.path("tokenId").asText().toLowerCase(Locale.ROOT)).equals(asset.tokenIdentifier())) {
            require(index==null && integer(token.path("szDecimals")).intValueExact()>=asset.decimals(),"independent HyperCore spot token is ambiguous or precision differs");index=integer(token.path("index")).intValueExact();
        }
        require(index!=null,"independent HyperCore exact spot token missing");BigInteger amount=BigInteger.ZERO;boolean found=false;
        for(var balance:state.path("balances"))if(integer(balance.path("token")).intValueExact()==index) {
            require(!found,"duplicate HyperCore token balance");found=true;amount=decimalAtomic(balance.path("total"),asset.decimals()).subtract(decimalAtomic(balance.path("hold"),asset.decimals())).max(BigInteger.ZERO);
        }
        return new Amounts(amount,asset.symbol().equals(asset.nativeSymbol())?amount:BigInteger.ZERO); // Direct spotSend has no HYPE gas expenditure.
    }
    private JsonNode viewResult(JsonNode response) {
        var values=response.path("result");require(values.isArray() && values.size()>0 && values.size()<=8192,"independent view result is absent/oversized");var bytes=new ByteArrayOutputStream();
        for(var value:values){require(value.isIntegralNumber() && value.canConvertToInt() && value.asInt()>=0 && value.asInt()<=255,"invalid independent view byte");bytes.write(value.asInt());}return json.readTree(bytes.toByteArray());
    }
    private static JsonNode runtime(JsonNode result){require(result.path("ok").isBoolean() && result.path("ok").asBoolean() && result.path("result").isObject(),"independent public runtime read failed");return result.path("result");}
    private static void getter(JsonNode result,int size){require(result.path("exit_code").isIntegralNumber() && result.path("exit_code").asInt()==0 && result.path("stack").isArray() && result.path("stack").size()==size,"independent TON getter failed");}
    private static String stackAddress(JsonNode value){require(Set.of("cell","slice").contains(value.path(0).asText()) && value.path(1).path("bytes").isString(),"TON getter did not return address cell");return CellSlice.beginParse(Cell.fromBocBase64(value.path(1).path("bytes").asText())).loadAddress().toRaw();}
    private static BigInteger integer(JsonNode value){require((value.isIntegralNumber() || value.isString()) && value.asText().matches("0|[1-9][0-9]{0,77}"),"independent balance is not an exact nonnegative atomic integer");return new BigInteger(value.asText());}
    private static BigInteger quantity(JsonNode value){require(value.isString() && value.asText().matches("0x[0-9a-fA-F]{1,64}"),"independent balance is not a bounded hex integer");return Numeric.toBigInt(value.asText());}
    private static BigInteger decimalAtomic(JsonNode value,int decimals){require(value.isNumber() || value.isString(),"independent decimal balance missing");var decimal=new BigDecimal(value.asText());require(decimal.signum()>=0 && decimal.precision()<=80 && decimal.scale()<=36 && decimal.scale()>=-80,"independent decimal balance out of range");return decimal.movePointRight(decimals).toBigIntegerExact();}
    private static BigInteger cardanoInteger(Cardano.BigInt value){var amount=switch(value.getBigIntCase()){case INT->BigInteger.valueOf(value.getInt());case BIG_U_INT->new BigInteger(1,value.getBigUInt().toByteArray());default->throw new IllegalArgumentException("Cardano owned balance integer absent/negative");};require(amount.signum()>=0,"negative Cardano owned balance");return amount;}
    private record Amounts(BigInteger available,BigInteger nativeAmount,String accountCodeState,
                           String delegatedTo,String observedBlockHash,Long observedBlockHeight){
        private Amounts(BigInteger available,BigInteger nativeAmount){this(available,nativeAmount,null,null,null,null);}
        private Amounts {require(available.signum()>=0 && nativeAmount.signum()>=0,"negative independent balance");}
    }
    public record Balance(String chain,String networkIdentity,String symbol,int decimals,int nativeDecimals,UUID keyId,String owner,BigInteger availableAtomic,BigInteger nativeAtomic,Instant observedAt,
                          String accountCodeState,String delegatedTo,String observedBlockHash,Long observedBlockHeight) { }
    private static void require(boolean value,String message){if(!value)throw new IllegalArgumentException(message);}
}
