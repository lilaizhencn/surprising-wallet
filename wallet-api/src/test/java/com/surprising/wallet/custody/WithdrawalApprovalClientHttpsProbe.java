package com.surprising.wallet.custody;

import com.surprising.wallet.common.approval.SigningCandidate;
import com.surprising.wallet.common.approval.WithdrawalIntent;
import com.surprising.wallet.config.WithdrawalApprovalProperties;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** Invoked only by isolated actual-HTTPS acceptance; configuration stays in its private temporary fixture. */
public final class WithdrawalApprovalClientHttpsProbe {
    private WithdrawalApprovalClientHttpsProbe() { }
    public static void main(String[] args) throws Exception {
        var json=new ObjectMapper();var input=json.readTree(Files.readAllBytes(Path.of(args[0])));
        var properties=json.treeToValue(input.path("profile"),WithdrawalApprovalProperties.class);
        try(var client=new WithdrawalApprovalClient(properties,json)) {
            Object result=switch(input.path("action").asText()) {
                case "SUBMIT"->client.submit(json.treeToValue(input.path("intent"),WithdrawalIntent.class));
                case "STATUS"->client.status(UUID.fromString(input.path("tenantId").asText()),UUID.fromString(input.path("requestId").asText()));
                case "AUTHORIZE"->client.authorize(json.treeToValue(input.path("candidate"),SigningCandidate.class));
                case "OBSERVE_KEY","OBSERVE_REFRESH","SIGN_WITH_STORED_GRANT"->WithdrawalApprovalNativeDispatchProbe.run(input,client,json);
                case "UTXO_BATCH"->UtxoApprovalNativeProbe.run(input,client,json);
                default->throw new IllegalArgumentException("unknown approval acceptance action");
            };
            System.out.println(json.writeValueAsString(result));
        }
    }
}
