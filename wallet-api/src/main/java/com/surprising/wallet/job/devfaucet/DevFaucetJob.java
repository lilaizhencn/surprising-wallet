package com.surprising.wallet.job.devfaucet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.custody.repository.CustodyRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import com.surprising.wallet.devfaucet.model.DevFaucetAmountGenerator;
import com.surprising.wallet.devfaucet.model.DevFaucetFunding;
import com.surprising.wallet.devfaucet.model.DevFaucetProperties;
import com.surprising.wallet.devfaucet.repository.DevFaucetRepository;
import com.surprising.wallet.devfaucet.service.DevFaucetRpcClient;

@Component
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
public class DevFaucetJob {
    private static final Logger log = LoggerFactory.getLogger(DevFaucetJob.class);

    private final DevFaucetProperties properties;
    private final DevFaucetRepository repository;
    private final DevFaucetRpcClient rpcClient;
    private final CustodyRepository custodyRepository;
    private final ObjectMapper objectMapper;
    private final DevFaucetAmountGenerator amounts;
    private final String environment;

    @Autowired
    public DevFaucetJob(DevFaucetProperties properties,
                 DevFaucetRepository repository,
                 DevFaucetRpcClient rpcClient,
                 CustodyRepository custodyRepository,
                 ObjectMapper objectMapper,
                 @Value("${sw.app.env.name:dev}") String environment) {
        this(properties, repository, rpcClient, custodyRepository, objectMapper,
                new DevFaucetAmountGenerator(), environment);
    }

    public DevFaucetJob(DevFaucetProperties properties,
                 DevFaucetRepository repository,
                 DevFaucetRpcClient rpcClient,
                 CustodyRepository custodyRepository,
                 ObjectMapper objectMapper,
                 DevFaucetAmountGenerator amounts,
                 String environment) {
        this.properties = properties;
        this.repository = repository;
        this.rpcClient = rpcClient;
        this.custodyRepository = custodyRepository;
        this.objectMapper = objectMapper;
        this.amounts = amounts;
        this.environment = environment;
    }

    @PostConstruct
    public void validate() {
        properties.validate(environment);
    }

    @Scheduled(
            fixedDelayString = "${sw.wallet.dev-faucet.delay:PT10S}",
            initialDelayString = "${sw.wallet.dev-faucet.delay:PT10S}")
    public void execute() {
        try {
            runOnce();
        } catch (RuntimeException error) {
            log.error("dev faucet scheduled pass failed", error);
        }
    }

    public void runOnce() {
        Duration staleAge = properties.getRequestTimeout().multipliedBy(2).plusSeconds(5);
        int stale = repository.recoverStaleSending(staleAge);
        int confirmed = repository.reconcileConfirmed();
        if (stale > 0 || confirmed > 0) {
            log.info("dev faucet reconciled confirmed={} staleUnknown={}", confirmed, stale);
        }

        for (DevFaucetRepository.Candidate candidate
                : repository.discover(properties.getBatchSize())) {
            repository.create(candidate, amountFor(candidate));
        }

        for (DevFaucetFunding funding
                : repository.due(properties.getBatchSize(), properties.getMaxAttempts())) {
            fund(funding);
        }
    }

    private void fund(DevFaucetFunding funding) {
        if (!repository.markSending(funding.id())) {
            return;
        }
        try {
            String txHash = rpcClient.send(funding);
            repository.markSent(funding.id(), txHash);
            audit(funding, "DEV_FAUCET.SENT", txHash, null);
            log.info("dev faucet sent chain={} asset={} purpose={} addressId={} txHash={}",
                    funding.chain(), funding.assetSymbol(), funding.purpose(),
                    funding.custodyAddressId(), txHash);
        } catch (DevFaucetRpcClient.RejectedException error) {
            repository.markFailed(funding.id(), error.getMessage(), properties.getRetryDelay());
            audit(funding, "DEV_FAUCET.FAILED", null, error.getMessage());
            log.warn("dev faucet RPC rejected fundingId={}: {}", funding.id(), error.getMessage());
        } catch (DevFaucetRpcClient.AmbiguousException error) {
            repository.markUnknown(funding.id(), error.getMessage());
            audit(funding, "DEV_FAUCET.UNKNOWN", null, error.getMessage());
            log.error("dev faucet RPC outcome is unknown; manual reconciliation required for {}",
                    funding.id(), error);
        }
    }

    BigDecimal amountFor(DevFaucetRepository.Candidate candidate) {
        if ("TENANT_GAS".equals(candidate.purpose())) {
            return switch (candidate.chain()) {
                case "BTC" -> properties.getBitcoin().getGasAmount();
                case "ETH" -> properties.getEvm().getGasAmount();
                default -> throw new IllegalArgumentException(
                        "unsupported dev faucet gas chain " + candidate.chain());
            };
        }
        return switch (candidate.assetSymbol()) {
            case "BTC" -> amounts.next(properties.getBitcoin().getCustomer());
            case "ETH" -> amounts.next(properties.getEvm().getCustomer());
            case "USDT" -> amounts.next(properties.getEvm().getUsdt());
            case "USDC" -> amounts.next(properties.getEvm().getUsdc());
            default -> throw new IllegalArgumentException(
                    "unsupported dev faucet asset " + candidate.assetSymbol());
        };
    }

    private void audit(DevFaucetFunding funding, String action, String txHash, String error) {
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "chain", funding.chain(),
                    "network", funding.network(),
                    "assetSymbol", funding.assetSymbol(),
                    "purpose", funding.purpose(),
                    "amount", funding.requestedAmount().toPlainString(),
                    "txHash", txHash == null ? "" : txHash,
                    "error", error == null ? "" : error));
            custodyRepository.audit(
                    funding.tenantId(), "SYSTEM", "dev-faucet", action,
                    "DEV_FAUCET_FUNDING", funding.id().toString(), "", details);
        } catch (JsonProcessingException | RuntimeException auditError) {
            log.error("failed to write dev faucet audit for {}", funding.id(), auditError);
        }
    }
}
