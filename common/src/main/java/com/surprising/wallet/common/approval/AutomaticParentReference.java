package com.surprising.wallet.common.approval;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Existing preparation reference domains, shared by both independent stores; no business-table joins are required. */
public final class AutomaticParentReference {
    private AutomaticParentReference() { }
    public static Parts decode(UUID tenant,String chain,String reference) {
        String prefix=switch(chain) {case "ADA"->"cardano-gas:";case "DOT"->"assethub-gas:";case "XMR"->"xmr-gas:";case "NEAR"->"near-storage:";default->throw new IllegalArgumentException("explicit parent preparation domain required");};
        if(reference==null || !reference.startsWith(prefix) || !reference.substring(prefix.length()).matches("[0-9a-f]{64}"))throw new IllegalArgumentException("invalid parent preparation reference");
        return new Parts(chain.equals("NEAR")?tenant+":":"",reference.substring(prefix.length()),chain.equals("NEAR")?"STORAGE_REGISTRATION":"GAS_TOPUP");
    }
    public record Parts(String hashPrefix,String parentHash,String scope) {
        public boolean matches(String type,String reference){return SigningAuthorization.hash((hashPrefix+type+":"+reference).getBytes(StandardCharsets.UTF_8)).equals(parentHash);}
    }
}
