package com.surprising.wallet.model.mpc;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Wallet transport identity and three independent signer endpoints; contains no wallet key material. */
@Data
@Component
@ConfigurationProperties(prefix = "sw.wallet.mpc")
public class MpcNodeProperties {
    private Path caFile;
    private Path certificateFile;
    private Path privateKeyFile;
    private Duration callTimeout = Duration.ofSeconds(5);
    private Duration operationTimeout = Duration.ofSeconds(30);
    private List<Node> nodes = new ArrayList<>();
    public record Node(String partyId, URI endpoint) { }
}
