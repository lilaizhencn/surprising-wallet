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

/**
 * 开发环境水龙头任务。
 * <p>
 * 仅在测试/dev 网络且 {@code sw.wallet.dev-faucet.enabled=true} 时启用。
 * 定时向配置的测试地址补充 BTC/ETH/USDT/USDC，并在低于阈值时自动创建补币请求。
 * 任务包含候选扫描、发送、重试、对账及审计闭环。
 *
 * @author atomex
 */
@Component
@ConditionalOnProperty(prefix = "sw.wallet.dev-faucet", name = "enabled", havingValue = "true")
public class DevFaucetJob {
    /** 日志记录器。 */
    private static final Logger log = LoggerFactory.getLogger(DevFaucetJob.class);

    /** 水龙头运行参数。 */
    private final DevFaucetProperties properties;
    /** 补币候选与发送状态持久化仓储。 */
    private final DevFaucetRepository repository;
    /** RPC 下发客户端。 */
    private final DevFaucetRpcClient rpcClient;
    /** 审计流水写入仓储。 */
    private final CustodyRepository custodyRepository;
    /** JSON 序列化工具，用于写审计详情。 */
    private final ObjectMapper objectMapper;
    /** 金额分配器，支持按配置生成不同测试金额。 */
    private final DevFaucetAmountGenerator amounts;
    /** 当前运行环境名称，用于配置校验。 */
    private final String environment;

    /**
     * 默认构造函数，由 Spring 注入依赖与环境变量。
     */
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

    /**
     * 可注入自定义金额生成器的构造函数，便于测试或替代实现。
     */
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

    /**
     * 服务启动时校验 dev-faucet 运行时配置是否满足环境要求。
     */
    @PostConstruct
    public void validate() {
        properties.validate(environment);
    }

    /**
     * 定时触发入口：每次执行一轮完整补币流程，异常仅记录日志不影响任务继续运行。
     */
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

    /**
     * 补币主流程：回收旧发送、对账确认、生成候选、执行发送。
     */
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

    /**
     * 对单条候选补币请求进行实际发送处理。
     */
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

    /**
     * 计算候选补币的目标金额（按资产或用途区分）。
     */
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

    /**
     * 写入 dev-faucet 审计记录，支持重试与对账追踪。
     */
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
