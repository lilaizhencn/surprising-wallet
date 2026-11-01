package com.surprising.wallet.custody;

import com.surprising.wallet.chain.signing.MpcNodes;
import com.surprising.wallet.common.approval.ApprovalRequest;
import com.surprising.wallet.common.approval.SigningCandidate;
import com.surprising.wallet.common.signing.MpcClient;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.CustodySecurityProperties;
import com.surprising.wallet.model.mpc.*;
import com.surprising.wallet.repository.*;
import com.surprising.wallet.service.*;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Unfunded isolated fixture: actual authority -> persisted Wallet proof -> actual native exchange, never chain broadcast. */
final class WithdrawalApprovalNativeDispatchProbe {
    private WithdrawalApprovalNativeDispatchProbe() { }
    static Object run(JsonNode input,WithdrawalApprovalClient client,ObjectMapper json) throws java.sql.SQLException {
        String url=System.getenv("APPROVAL_DB_URL");
        if(url==null || !url.matches("^jdbc:postgresql://127\\.0\\.0\\.1:5432/surprising_wallet_test_[a-z0-9_]+$"))
            throw new IllegalArgumentException("native dispatch acceptance requires its isolated existing-local-PG18 test database");
        var data=new DriverManagerDataSource(url,System.getenv("APPROVAL_DB_USERNAME"),System.getenv("APPROVAL_DB_PASSWORD"));
        com.surprising.wallet.common.security.DatabaseRoleIsolation.wallet(data);
        var jdbc=new JdbcTemplate(data);var manager=new DataSourceTransactionManager(data);var transaction=new TransactionTemplate(manager);
        if(jdbc.queryForObject("select current_setting('server_version_num')::int/10000",Integer.class)!=18)throw new IllegalStateException("PG18 required");
        String action=input.path("action").asText();
        var candidate=input.has("candidate")?json.treeToValue(input.path("candidate"),SigningCandidate.class):null;
        var command=candidate==null?null:candidate.command();var execution=candidate==null?null:candidate.execution();
        var receipt=input.has("approvalReceipt")?json.treeToValue(input.path("approvalReceipt"),ApprovalRequest.class):null;var intent=receipt==null?null:receipt.intent();
        var ceremony=json.treeToValue(input.path("ceremony"),MpcClient.Request.class);
        var refresh=input.has("refresh")?json.treeToValue(input.path("refresh"),MpcClient.Request.class):null;
        UUID tenant=ceremony.tenantId(),keyId=ceremony.keyId();
        String chain=candidate==null?input.path("chain").asText("ETH"):command.chain().name();
        String keyGroup=com.surprising.wallet.common.chain.ChainType.valueOf(chain).isEvm()?"EVM":chain;
        String nativeSymbol=chain.equals("APTOS")?"APT":"ETH";
        String publicKey=input.path("publicKeyHex").asText();
        var tenants=new CustodyTenantTableRepository(jdbc);var keys=new WalletMpcKeyRepository(jdbc);var epochs=new WalletMpcKeyEpochRepository(jdbc,json);
        var requests=new WalletMpcSignRequestRepository(jdbc);var attempts=new WalletMpcSignAttemptRepository(jdbc,json);var approvals=new WalletWithdrawalApprovalRepository(jdbc,json);
        transaction.executeWithoutResult(status->{
            if(action.equals("OBSERVE_KEY")) {
                if(tenants.findById(tenant).isEmpty())tenants.insert(tenant,"dispatch-"+tenant,"Isolated native dispatch acceptance");
                int namespace=tenants.findById(tenant).orElseThrow().derivationNamespace();
                keys.reserve(keyId,tenant,keyGroup,1,namespace,0,ceremony.algorithm().name());epochs.create(ceremony);return;
            }
            if(action.equals("OBSERVE_REFRESH")) {
                epochs.create(refresh,"acceptance","actual same-public epoch refresh");keys.beginRefresh(tenant,keyId,1);return;
            }
            int namespace=tenants.findById(tenant).orElseThrow().derivationNamespace();
            String sender;
            if(chain.equals("APTOS"))sender=com.surprising.wallet.sdk.signing.aptos.MpcAptosTransactionCodec.address(HexFormat.of().parseHex(publicKey));
            else {
                var point=org.bitcoinj.crypto.ECKey.fromPublicOnly(HexFormat.of().parseHex(publicKey)).getPubKeyPoint().getEncoded(false);
                sender="0x"+org.web3j.crypto.Keys.getAddress(HexFormat.of().formatHex(point,1,point.length));
            }
            long addressId=jdbc.queryForObject("""
                    insert into chain_address(tenant_id,mpc_key_id,chain,asset_symbol,account_id,user_id,biz,address_index,address,owner_address,derivation_path,wallet_role,enabled)
                    values (?,?,?,?,'native-dispatch-acceptance',1,?,0,?,?,'mpc:fixture','HOT_WALLET',true) returning id
                    """,Long.class,tenant,command.keyId(),chain,nativeSymbol,namespace,sender,sender);
            UUID custodyId=UUID.randomUUID();
            jdbc.update("""
                    insert into custody_address(id,tenant_id,chain_address_id,chain,network,address,subject,source,status,derivation_subject,derivation_child)
                    values (?,?,?,?,?,?,'native-dispatch-acceptance','API','ACTIVE',1,0)
                    """,custodyId,tenant,addressId,chain,intent.network(),sender);
            var orders=new WithdrawalOrderRepository(jdbc);
            long orderId=orders.createForTenant(tenant,intent.businessReference(),1,chain,intent.assetSymbol(),sender,"native-dispatch-acceptance",intent.recipient(),intent.amount(),BigDecimal.ZERO);
            orders.updateStatus(tenant,chain,intent.businessReference(),"SIGNING",sender,null,null);
            jdbc.update("insert into ledger_balance(tenant_id,chain,asset_symbol,account_id,available_balance,locked_balance,total_balance) values (?,?,?,'native-dispatch-acceptance',0,?,?)",tenant,chain,intent.assetSymbol(),intent.amount(),intent.amount());
            new CustodyWithdrawalRepository(jdbc).insert(intent.id(),tenant,custodyId,orderId,intent.businessReference(),intent.externalReference(),"native-dispatch-"+intent.id(),
                    chain,intent.assetSymbol(),intent.recipient(),intent.amount(),BigDecimal.ZERO,"FROZEN","API_KEY",intent.initiatorId().toString(),intent.subject(),intent.fundingSource());
            approvals.insert(intent.id(),orderId,intent);approvals.record(tenant,intent.id(),"APPROVED",receipt);
            if(command.chain().isEvm()) {
                var payload=json.readValue(HexFormat.of().parseHex(command.payloadHex()),com.surprising.wallet.common.signing.EvmSigningPayload.class);
                new EvmNonceRepository(jdbc).reserve(chain,sender.toLowerCase(java.util.Locale.ROOT),payload.raw().getNonce(),command.reservationOwner());
            } else if(chain.equals("APTOS")) {
                var payload=json.readValue(HexFormat.of().parseHex(command.payloadHex()),com.surprising.wallet.common.signing.AptosSigningPayload.class);
                new AccountSequenceRepository(jdbc).reserve(chain,sender,payload.unsigned().sequence(),command.reservationOwner());
            } else throw new IllegalArgumentException("this native dispatch fixture needs an explicit nonce allocation adapter");
            requests.reserve(candidate.signingRequestId(),command,command.businessReferences(json));attempts.create(candidate.signingRequestId(),execution,null,null);
            requests.pointToAttempt(tenant,candidate.signingRequestId(),execution.sessionId());
        });
        var security=new CustodySecurityProperties();byte[] aes=new byte[32];new java.security.SecureRandom().nextBytes(aes);
        security.setSecretMasterKey(HexFormat.of().formatHex(aes));java.util.Arrays.fill(aes,(byte)0);var crypto=new CustodyCryptoService(security);crypto.validateConfiguration();
        var authorizations=com.surprising.wallet.testutil.MpcAuthorizationTestSupport.service(jdbc,json,manager,client);
        var signing=new MpcSigningService(tenants,keys,epochs,requests,attempts,crypto,json,manager,authorizations);
        var nativeProfile=input.path("nativeProfile");var properties=new MpcNodeProperties();
        properties.setCaFile(Path.of(nativeProfile.path("caFile").asText()));properties.setCertificateFile(Path.of(nativeProfile.path("certificateFile").asText()));
        properties.setPrivateKeyFile(Path.of(nativeProfile.path("privateKeyFile").asText()));properties.setCallTimeout(Duration.ofSeconds(3));properties.setOperationTimeout(Duration.ofSeconds(20));
        properties.setNodes(java.util.stream.StreamSupport.stream(nativeProfile.path("nodes").spliterator(),false)
                .map(node->new MpcNodeProperties.Node(node.path("partyId").asText(),URI.create(node.path("endpoint").asText()))).toList());
        try(var nodes=new MpcNodes(properties)) {
            if(action.equals("OBSERVE_KEY") || action.equals("OBSERVE_REFRESH")) {
                var key=action.equals("OBSERVE_KEY")?signing.advanceKey(tenant,keyId,nodes.clients()):signing.advanceRefresh(tenant,keyId,2,nodes.clients());
                if(key.state()!=MpcKey.State.ACTIVE || !key.publicKeyHex().equals(publicKey) || epochs.registration(tenant,keyId,key.activeEpoch()).isEmpty())
                    throw new IllegalStateException("actual native ceremony did not produce a durable authority registration receipt");
                var result=new java.util.LinkedHashMap<String,Object>(Map.of("state","ACTIVE","epoch",key.activeEpoch(),"publicKeyHex",key.publicKeyHex(),"independentlyRegistered",true));
                if(com.surprising.wallet.common.chain.ChainType.valueOf(chain).isBitcoinLike()) {
                    var network=com.surprising.wallet.sdk.bitcoinj.core.BitcoinLikeNetwork.REGTEST;
                    String address=com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec.address(chain,network,publicKey);
                    result.put("address",address);result.put("scriptPubKeyHex",com.surprising.wallet.sdk.signing.MpcUtxoTransactionCodec.outputScriptHex(chain,network,address));
                }
                return result;
            }
            authorizations.registerKey(tenant,keyId); // Cached receipt must work while one holder is offline.
            byte[] signature=signing.awaitSignature(tenant,candidate.signingRequestId(),nodes);
            var attempt=attempts.find(tenant,execution.sessionId()).orElseThrow();var selected=requests.find(tenant,candidate.signingRequestId()).orElseThrow();
            if(attempt.authorization()==null || attempt.state()!=MpcSignAttempt.State.COMPLETED || selected.state()!=MpcSignRequest.State.COMPLETED)
                throw new IllegalStateException("actual signed fact is missing its committed original authority proof");
            BigDecimal locked=jdbc.queryForObject("select locked_balance from ledger_balance where tenant_id=? and chain=? and asset_symbol=? and account_id='native-dispatch-acceptance'",BigDecimal.class,tenant,chain,intent.assetSymbol());
            if(locked.compareTo(intent.amount())!=0)throw new IllegalStateException("signing released or settled unconfirmed principal");
            return Map.of("status","SIGNED","signatureHex",HexFormat.of().formatHex(signature),"proofPersisted",true,
                    "authorizationAudits",jdbc.queryForObject("select count(*) from custody_audit_log where tenant_id=? and resource_id=? and action='MPC.AUTHORIZE'",Integer.class,tenant,candidate.signingRequestId().toString()),"principalFrozen",true);
        }
    }
}
