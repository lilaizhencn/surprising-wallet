package com.surprising.wallet.chain.hypercore;

import com.surprising.wallet.sdk.signing.hypercore.MpcHyperCoreActionCodec;
import com.surprising.wallet.common.signing.HyperCoreSigningPayload;

import com.surprising.wallet.common.signing.MpcSignCommand;

import com.surprising.wallet.common.chain.ChainAddressRecord;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.chain.DepositEvent;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.DepositService;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real incoming ledger events and their cursor share the mandatory deposit/projection transaction. */
@Service
@RequiredArgsConstructor
public class HyperCoreDepositService {
    static final int LEDGER_PAGE_THRESHOLD=500;
    private final HyperCoreLedgerCursorRepository cursors;
    private final ChainAddressRepository addresses;
    private final HyperCoreActionRepository actions;
    private final WalletMpcSignRequestRepository requests;
    private final WithdrawalOrderRepository withdrawals;
    private final CollectionRecordRepository collections;
    private final HyperCoreTokenMetadataRepository metadata;
    private final TokenConfigRepository tokens;
    private final DepositService deposits;
    private final ObjectMapper json;

    public List<String> assets(String network) {
        var symbols=new java.util.LinkedHashSet<String>();symbols.add("USDC");
        tokens.listByChainAndNetwork("HYPERCORE",network).forEach(token->symbols.add(token.symbol()));
        return List.copyOf(symbols);
    }
    public long position(ChainAddressRecord address,String network,String symbol) {
        requireOwner(address);require(assets(network).contains(symbol),"unsupported HyperCore scan asset");
        cursors.ensure(address.getTenantId(),network,address.getId(),symbol,address.getAddress());
        return cursors.position(address.getTenantId(),network,address.getId(),symbol,address.getAddress(),false);
    }

    @Transactional(rollbackFor=Throwable.class)
    public boolean recordPage(ChainAddressRecord address,String network,String symbol,long expected,JsonNode records) {
        requireOwner(address);require(assets(network).contains(symbol),"unsupported HyperCore scan asset");
        long current=cursors.position(address.getTenantId(),network,address.getId(),symbol,address.getAddress(),true);
        if(current!=expected)return false;
        require(records!=null && records.isArray() && records.size()<=2000,"HyperCore ledger response is not a bounded event list");
        long last=expected;
        var owned=addresses.listMpcAddresses("HYPERCORE");
        for(var record:records) {
            require(record.path("time").isIntegralNumber() && record.path("time").canConvertToLong()
                    && record.path("time").asLong()>=last,"HyperCore ledger timestamps are missing or unordered");
            last=record.path("time").asLong();
            creditIncoming(address,network,symbol,record,owned);
        }
        require(records.size()<LEDGER_PAGE_THRESHOLD || last>expected,"HyperCore ledger timestamp boundary is truncated; cursor retained");
        require(cursors.advance(address.getTenantId(),network,address.getId(),symbol,expected,last)==1,"HyperCore ledger cursor raced");
        return true;
    }

    private void creditIncoming(ChainAddressRecord address,String network,String symbol,JsonNode record,List<ChainAddressRecord> owned) {
        var delta=record.path("delta");String type=delta.path("type").asText();
        if(!"send".equals(type) && !"spotTransfer".equals(type))return;
        if(!address.getAddress().equalsIgnoreCase(delta.path("destination").asText()))return;
        if("send".equals(type) && !"spot".equals(delta.path("destinationDex").asText()))return;
        if(!symbol.equals(delta.path("token").asText()))return;
        require(record.path("hash").asText().matches("0x[0-9a-f]{64}") && record.path("time").asLong()>0
                && delta.path("user").asText().matches("0x[0-9a-fA-F]{40}")
                && delta.path("nonce").isIntegralNumber() && delta.path("nonce").canConvertToLong()
                && delta.path("nonce").asLong()>0 && delta.path("amount").isTextual(),"HyperCore incoming transfer identity is incomplete");
        var amount=new BigDecimal(delta.path("amount").asText());require(amount.signum()>0,"HyperCore incoming amount must be positive");
        var token=metadata.findTransferToken(network,symbol).orElseThrow();
        if("USDC".equals(symbol)) {
            require(token.canonical(),"HyperCore USDC is not canonical");amount.movePointRight(6).toBigIntegerExact();
        } else {
            var definition=tokens.listByChainAndNetwork("HYPERCORE",network).stream().filter(item->item.symbol().equals(symbol)).findFirst().orElseThrow();
            require(definition.contractAddress()!=null && definition.contractAddress().equalsIgnoreCase(token.tokenId())
                    && definition.decimals()==token.decimals(),"HyperCore incoming token identity differs");
            amount.movePointRight(definition.decimals()).toBigIntegerExact();
        }
        var senders=owned.stream().filter(owner->owner.getAddress().equalsIgnoreCase(delta.path("user").asText())).toList();
        if(!senders.isEmpty() && internalCollection(address,record,senders))return;
        // This API supplies a ledger time and transaction hash, not a block height/hash.
        deposits.recordAndCredit(new DepositEvent(ChainType.HYPERCORE,symbol,record.path("hash").asText(),
                delta.path("user").asText().toLowerCase(Locale.ROOT),address.getAddress(),amount,0,null,1,
                "USDC".equals(symbol)?null:token.tokenId(),record.toString()),0,1,address.getAccountId());
    }

    private boolean internalCollection(ChainAddressRecord recipient,JsonNode record,List<ChainAddressRecord> senders) {
        var delta=record.path("delta");String sender=delta.path("user").asText().toLowerCase(Locale.ROOT);
        String id="HC-spotSend-"+sender+"-"+delta.path("nonce").asLong();
        var action=actions.find(id).orElseThrow(()->new IllegalStateException("owned HyperCore transfer has no original selected action"));
        require(senders.stream().allMatch(owner->owner.getTenantId().equals(action.tenantId())),"HyperCore sender tenant is ambiguous");
        var request=requests.find(action.tenantId(),action.requestId()).orElseThrow();var command=request.command();
        require(command.chain()==ChainType.HYPERCORE && command.payloadEncoding().equals("HYPERCORE_JSON")
                && request.state()==MpcSignRequest.State.COMPLETED,"HyperCore owned incoming transfer has no completed original intent");
        var payload=json.readValue(HexFormat.of().parseHex(command.payloadHex()),HyperCoreSigningPayload.class);
        require(HexFormat.of().formatHex(MpcHyperCoreActionCodec.digest(payload.unsigned())).equals(command.preimageHex())
                && json.readTree(action.raw()).path("action").equals(MpcHyperCoreActionCodec.restore(payload.unsigned()))
                && sender.equals(payload.unsigned().owner()),"HyperCore owned transfer differs from its selected intent");
        HyperCoreExecutionReceipt.verify(payload.unsigned(),record);
        if(command.businessType()==MpcSignCommand.BusinessType.COLLECTION) {
            var business=collections.lockForSubmission(action.tenantId(),"HYPERCORE",command.businessReference()).orElseThrow();
            require(action.tenantId().equals(recipient.getTenantId()) && Objects.equals(id,business.getTxHash())
                    && business.getToAddress().equalsIgnoreCase(recipient.getAddress())
                    && business.getFromAddress().equalsIgnoreCase(sender) && business.getAssetSymbol().equals(payload.symbol())
                    && business.getAmount().compareTo(payload.amount())==0,"HyperCore collection ownership differs");
            return true;
        }
        require(command.businessType()==MpcSignCommand.BusinessType.WITHDRAWAL,"unsupported owned HyperCore incoming transfer");
        var business=withdrawals.find("HYPERCORE",command.businessReference(),action.tenantId()).orElseThrow();
        require(Objects.equals(id,business.getTxHash()) && business.getFromAddress().equalsIgnoreCase(sender)
                && business.getToAddress().equalsIgnoreCase(recipient.getAddress()) && business.getAssetSymbol().equals(payload.symbol())
                && business.getAmount().compareTo(payload.amount())==0,"HyperCore owned withdrawal differs");
        return false;
    }

    private void requireOwner(ChainAddressRecord address) {
        var actual=addresses.findOwned(address.getTenantId(),address.getId()).orElseThrow();
        require("HYPERCORE".equals(actual.getChain()) && actual.getMpcKeyId()!=null
                && actual.getAddress().equals(address.getAddress()) && actual.getAssetSymbol().equals(address.getAssetSymbol())
                && actual.getAccountId().equals(address.getAccountId()) && "DEPOSIT".equals(actual.getWalletRole()),"HyperCore scan owner differs");
    }
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalStateException(message);}
}
