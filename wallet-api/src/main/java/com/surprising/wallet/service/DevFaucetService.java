package com.surprising.wallet.service;

import com.surprising.wallet.devfaucet.DevFaucetAmountGenerator;
import com.surprising.wallet.devfaucet.DevFaucetFunding;
import com.surprising.wallet.devfaucet.DevFaucetProperties;
import com.surprising.wallet.repository.CustodyRepository;
import com.surprising.wallet.repository.DevFaucetRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

/**
 * 开发环境水龙头业务服务，负责候选发现、补币发送、重试、对账和审计。
 */
@Service
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
@Slf4j
public class DevFaucetService {
    /** 水龙头运行参数。 */
    private final DevFaucetProperties properties;
    /** 补币候选与发送状态仓储。 */
    private final DevFaucetRepository repository;
    /** RPC 下发客户端。 */
    private final DevFaucetRpcClient rpcClient;
    /** 审计流水仓储。 */
    private final CustodyRepository custodyRepository;
    /** 审计详情序列化器。 */
    private final ObjectMapper objectMapper;
    /** 测试金额生成器。 */
    private final DevFaucetAmountGenerator amounts;
    /** 当前运行环境。 */
    private final String environment;

    /** 由 Spring 注入生产依赖。 */
    @Autowired
    public DevFaucetService(
            DevFaucetProperties properties,
            DevFaucetRepository repository,
            DevFaucetRpcClient rpcClient,
            CustodyRepository custodyRepository,
            ObjectMapper objectMapper,
            @Value("${sw.app.env.name:dev}") String environment) {
        this(properties, repository, rpcClient, custodyRepository, objectMapper,
                new DevFaucetAmountGenerator(), environment);
    }

    /** 允许测试替换金额生成器的构造函数。 */
    public DevFaucetService(
            DevFaucetProperties properties,
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

    /** 启动时校验水龙头配置和运行环境。 */
    @PostConstruct
    public void validate() {
        properties.validate(environment);
    }

    /** 执行一轮回收、对账、候选创建和补币发送。 */
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

    /** 计算候选补币的目标金额。 */
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

    /** 发送单条补币请求并记录结果。 */
    private void fund(DevFaucetFunding funding) {
        if (!repository.markSending(funding.id())) {
            return;
        }
        try {
            String transactionHash = rpcClient.send(funding);
            repository.markSent(funding.id(), transactionHash);
            audit(funding, "DEV_FAUCET.SENT", transactionHash, null);
        } catch (DevFaucetRpcClient.RejectedException error) {
            repository.markFailed(funding.id(), error.getMessage(), properties.getRetryDelay());
            audit(funding, "DEV_FAUCET.FAILED", null, error.getMessage());
        } catch (DevFaucetRpcClient.AmbiguousException error) {
            repository.markUnknown(funding.id(), error.getMessage());
            audit(funding, "DEV_FAUCET.UNKNOWN", null, error.getMessage());
        }
    }

    /** 写入水龙头审计详情，审计失败不影响发送状态处理。 */
    private void audit(DevFaucetFunding funding, String action, String transactionHash, String error) {
        try {
            String details = objectMapper.writeValueAsString(Map.of(
                    "chain", funding.chain(),
                    "network", funding.network(),
                    "assetSymbol", funding.assetSymbol(),
                    "purpose", funding.purpose(),
                    "amount", funding.requestedAmount().toPlainString(),
                    "txHash", transactionHash == null ? "" : transactionHash,
                    "error", error == null ? "" : error));
            custodyRepository.audit(
                    funding.tenantId(), "SYSTEM", "dev-faucet", action,
                    "DEV_FAUCET_FUNDING", funding.id().toString(), null, details);
        } catch (RuntimeException auditError) {
            log.error("failed to write dev faucet audit for {}", funding.id(), auditError);
        }
    }
}
