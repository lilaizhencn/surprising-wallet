package com.surprising.wallet.approval;

import com.surprising.wallet.common.approval.SigningGrant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ApprovalArchitectureTest {
    @Test void independentModuleHasNoWalletApplicationDependencyAndRepositoriesOwnOneTable() throws Exception {
        var root=Path.of(System.getProperty("user.dir"));
        var pom=Files.readString(root.resolve("pom.xml"));
        assertFalse(pom.contains("<artifactId>wallet-api</artifactId>"));
        var source=root.resolve("src/main/java/com/surprising/wallet/approval");
        var tables=Map.ofEntries(Map.entry("Tenant","tenant"),Map.entry("Member","member"),Map.entry("MemberRevocation","member_revocation"),Map.entry("Policy","policy"),Map.entry("Request","request"),
                Map.entry("Decision","decision"),Map.entry("Audit","audit"),Map.entry("Session","session"),Map.entry("Key","key"),
                Map.entry("SigningTransaction","signing_transaction"),Map.entry("SigningVersion","signing_version"),Map.entry("SigningSlot","signing_slot"),
                Map.entry("SigningGrant","signing_grant"),Map.entry("FeeReservation","fee_reservation"),Map.entry("SpendLock","spend_lock"),Map.entry("AutomaticOperation","automatic_operation"));
        for(var entry:tables.entrySet()) {
            String body=Files.readString(source.resolve("repository/Approval"+entry.getKey()+"Repository.java"));
            assertTrue(body.contains("@Repository"));assertTrue(body.contains("approval."+entry.getValue()));
            for(var other:tables.values()) if(!other.equals(entry.getValue()))
                assertFalse(body.matches("(?s).*\\b(from|join|into|update)\\s+approval\\."+other+"\\b.*"),entry.getKey()+" must not query "+other);
        }
        try(var files=Files.walk(source)) {
            for(var file:files.filter(p->p.toString().endsWith(".java")).toList()) {
                var body=Files.readString(file);
                assertFalse(body.contains("import com.surprising.wallet.service.") || body.contains("import com.surprising.wallet.repository."));
                if(!file.toString().contains("/repository/")) assertFalse(body.matches("(?s).*\\b(select|update|insert into)\\s+approval\\..*"));
                if(file.toString().contains("/service/") || file.toString().contains("/controller/") || file.toString().contains("/job/")) assertFalse(body.contains("JdbcTemplate"));
            }
        }
    }
}
