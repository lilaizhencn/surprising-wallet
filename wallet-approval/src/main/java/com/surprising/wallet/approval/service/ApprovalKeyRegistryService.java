package com.surprising.wallet.approval.service;

import com.surprising.wallet.approval.gateway.ApprovalNativeRegistryClient;
import com.surprising.wallet.approval.model.RegisteredMpcKey;
import com.surprising.wallet.approval.repository.*;
import com.surprising.wallet.common.chain.ChainType;
import com.surprising.wallet.common.signing.MpcClient;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class ApprovalKeyRegistryService {
    private final ApprovalNativeRegistryClient nativeRegistry;
    private final ApprovalKeyRepository keys;
    private final ApprovalTenantRepository tenants;
    private final ApprovalAuditRepository audit;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    public ApprovalKeyRegistryService(ApprovalNativeRegistryClient nativeRegistry,ApprovalKeyRepository keys,ApprovalTenantRepository tenants,
            ApprovalAuditRepository audit,PlatformTransactionManager manager,ObjectMapper json) {
        this.nativeRegistry=nativeRegistry;this.keys=keys;this.tenants=tenants;this.audit=audit;transaction=new TransactionTemplate(manager);this.json=json;
    }
    public RegisteredMpcKey register(String group,MpcClient.Request request,String serviceIdentity) {
        require(request!=null && group!=null,"key registration is required");
        require(groupAlgorithm(group)==request.algorithm(),"key group algorithm differs");
        if(request.algorithm()==MpcClient.Algorithm.MONERO_NATIVE_2_OF_3) {
            var policy=json.readTree(HexFormat.of().parseHex(request.messageHex()));
            require((group.equals("XMR_ROOT")?"ROOT":"ADDRESS").equals(policy.path("kind").asText()),"Monero registry group differs from actual key ceremony");
        }
        // Native HTTP runs before taking database locks; caller-supplied result JSON is never an argument.
        var ceremony=nativeRegistry.observe(request);
        require(ceremony.request().equals(request),"observed ceremony descriptor differs");
        return transaction.execute(status->{
            require(tenants.lockActive(request.tenantId()),"independent tenant is not enrolled or disabled");
            var old=keys.lock(request.tenantId(),request.keyId());
            if(old.isPresent()) {
                var current=old.get();
                require(current.state().equals("ACTIVE") && current.algorithm().equals(request.algorithm().name())
                        && current.keyGroup().equals(group) && current.publicKeyHex().equals(ceremony.publicKeyHex())
                        && java.util.Objects.equals(current.publicAddress(),ceremony.publicAddress()),"existing independent key identity differs");
                if(current.epoch()==request.epoch()) return current;
                require(request.operation()==MpcClient.Operation.REFRESH && request.epoch()==current.epoch()+1,"key refresh skipped or reversed an epoch");
                require(keys.refresh(current,ceremony)==1,"key refresh registration raced");
            } else {
                require(request.operation()==MpcClient.Operation.CREATE_KEY && request.epoch()==1,"first key registration requires original epoch-one ceremony");
                keys.insert(group,ceremony);
            }
            audit.insert(request.tenantId(),"SERVICE",serviceIdentity,"KEY.REGISTER",request.keyId(),
                    Map.of("keyGroup",group,"epoch",request.epoch(),"sessionId",request.sessionId().toString(),"holders",ceremony.observations()));
            return keys.find(request.tenantId(),request.keyId()).orElseThrow();
        });
    }
    private static MpcClient.Algorithm groupAlgorithm(String group) {
        if(group.equals("EVM")) return MpcClient.Algorithm.ECDSA_SECP256K1;
        if(group.equals("XMR") || group.equals("XMR_ROOT")) return MpcClient.Algorithm.MONERO_NATIVE_2_OF_3;
        var chain=ChainType.valueOf(group);require(!chain.isEvm(),"EVM chains use the shared EVM key group");
        return switch(chain) {
            case BTC,LTC,DOGE,BCH,TRON,XRP,HYPERCORE -> MpcClient.Algorithm.ECDSA_SECP256K1;
            case SOLANA,TON,APTOS,SUI,ADA,DOT,NEAR -> MpcClient.Algorithm.ED25519;
            default -> throw new IllegalArgumentException("unsupported independent key group");
        };
    }
    private static void require(boolean value,String message) { if(!value) throw new IllegalArgumentException(message); }
}
