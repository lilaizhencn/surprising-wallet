package com.surprising.wallet.devfaucet;

import tools.jackson.databind.ObjectMapper;
import com.surprising.wallet.repository.CustodyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import com.surprising.wallet.repository.DevFaucetRepository.Candidate;
import com.surprising.wallet.devfaucet.model.DevFaucetAmountGenerator;
import com.surprising.wallet.devfaucet.model.DevFaucetFunding;
import com.surprising.wallet.job.devfaucet.DevFaucetJob;
import com.surprising.wallet.devfaucet.model.DevFaucetProperties;
import com.surprising.wallet.repository.DevFaucetRepository;
import com.surprising.wallet.devfaucet.service.DevFaucetRpcClient;

/**
 * 验证 {@code DevFaucetJobTest} 覆盖的业务流程、边界条件和异常行为。
 */
class DevFaucetJobTest {
    /**
     * 验证 {@code selectsTheProductionConstructorWhenSpringCreatesTheJob} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void selectsTheProductionConstructorWhenSpringCreatesTheJob() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new MapPropertySource("dev-faucet-test", Map.of(
                            "sw.wallet.dev-faucet.enabled", "true",
                            "sw.app.env.name", "test")));
            context.registerBean(DevFaucetProperties.class,
                    DevFaucetPropertiesTest::validProperties);
            context.registerBean(DevFaucetRepository.class,
                    () -> new FakeRepository(List.of()));
            context.registerBean(DevFaucetRpcClient.class, FakeRpcClient::new);
            context.registerBean(CustodyRepository.class, FakeCustodyRepository::new);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.register(DevFaucetJob.class);
            context.refresh();

            assertEquals(1, context.getBeansOfType(DevFaucetJob.class).size());
        }
    }

    /**
     * 验证 {@code fundsNativeAndTokensOncePerApiAddressAndNativeOnlyForGasAddress} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    @Test
    void fundsNativeAndTokensOncePerApiAddressAndNativeOnlyForGasAddress() {
        UUID tenantId = UUID.randomUUID();
        UUID customerAddressId = UUID.randomUUID();
        UUID gasAddressId = UUID.randomUUID();
        FakeRepository repository = new FakeRepository(List.of(
                candidate(tenantId, customerAddressId, "ETH", "ETH", null, 18,
                        "CUSTOMER_DEPOSIT"),
                candidate(tenantId, customerAddressId, "ETH", "USDT", "0x"
                                + "11".repeat(20), 6, "CUSTOMER_DEPOSIT"),
                candidate(tenantId, customerAddressId, "ETH", "USDC", "0x"
                                + "22".repeat(20), 6, "CUSTOMER_DEPOSIT"),
                candidate(tenantId, gasAddressId, "ETH", "ETH", null, 18,
                        "TENANT_GAS")));
        FakeRpcClient rpc = new FakeRpcClient();
        DevFaucetProperties properties = DevFaucetPropertiesTest.validProperties();
        DevFaucetJob job = new DevFaucetJob(
                properties, repository, rpc, new FakeCustodyRepository(),
                new ObjectMapper(), new DevFaucetAmountGenerator(new java.util.Random(1)), "test");

        job.validate();
        job.runOnce();
        job.runOnce();

        assertEquals(4, rpc.sent.size());
        assertEquals(List.of("ETH", "USDT", "USDC", "ETH"),
                rpc.sent.stream().map(DevFaucetFunding::assetSymbol).toList());
        assertEquals(4, repository.sent);
        assertEquals(new BigDecimal("1.000000"),
                rpc.sent.getLast().requestedAmount());
    }

    /**
     * 验证 {@code candidate} 对应的测试场景，明确输入、预期结果和异常边界。
     */
    private static DevFaucetRepository.Candidate candidate(
            UUID tenantId, UUID addressId, String chain, String asset,
            String contract, int decimals, String purpose) {
        return new DevFaucetRepository.Candidate(
                tenantId, addressId, chain, "devtest", asset, purpose,
                "0x" + "ab".repeat(20), contract, decimals);
    }

    /**
     * 测试替身 {@code FakeRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRepository extends DevFaucetRepository {
        /**
         * 保存 {@code undiscovered}，用于承载当前测试夹具的配置或运行数据。
         */
        private List<Candidate> undiscovered;
        /**
         * 保存 {@code due}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<DevFaucetFunding> due = new ArrayList<>();
        /**
         * 保存 {@code sent}，用于承载当前测试夹具的配置或运行数据。
         */
        private int sent;

        /**
         * 验证 {@code FakeRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeRepository(List<Candidate> candidates) {
            super(null);
            this.undiscovered = new ArrayList<>(candidates);
        }

        /**
         * 验证 {@code discover} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<Candidate> discover(int limit) {
            List<Candidate> result = List.copyOf(undiscovered);
            undiscovered = List.of();
            return result;
        }

        /**
         * 验证 {@code create} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public boolean create(Candidate candidate, BigDecimal amount) {
            due.add(new DevFaucetFunding(
                    UUID.randomUUID(), candidate.tenantId(), candidate.custodyAddressId(),
                    candidate.chain(), candidate.network(), candidate.assetSymbol(),
                    candidate.purpose(), candidate.address(), candidate.contractAddress(),
                    candidate.decimals(), amount, 0));
            return true;
        }

        /**
         * 验证 {@code due} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public List<DevFaucetFunding> due(int limit, int maxAttempts) {
            List<DevFaucetFunding> result = List.copyOf(due);
            due.clear();
            return result;
        }

        /** 标记水龙头资金记录已进入发送状态。 */
        @Override public boolean markSending(UUID id) { return true; }

        /** 标记水龙头资金记录已发送，并保存交易哈希。 */
        @Override public void markSent(UUID id, String txHash) { sent++; }

        /** 回收超过发送时限仍未完成的水龙头资金记录。 */
        @Override public int recoverStaleSending(Duration age) { return 0; }

        /** 对已确认的水龙头资金记录执行对账并返回处理数量。 */
        @Override public int reconcileConfirmed() { return 0; }
    }

    /**
     * 测试替身 {@code FakeRpcClient}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeRpcClient implements DevFaucetRpcClient {
        /**
         * 保存 {@code sent}，用于承载当前测试夹具的配置或运行数据。
         */
        private final List<DevFaucetFunding> sent = new ArrayList<>();

        /**
         * 验证 {@code send} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public String send(DevFaucetFunding funding) {
            sent.add(funding);
            return "0x" + String.format("%064x", sent.size());
        }
    }

    /**
     * 测试替身 {@code FakeCustodyRepository}，用于隔离外部依赖并验证调用参数和状态变化。
     */
    private static final class FakeCustodyRepository extends CustodyRepository {
        /**
         * 验证 {@code FakeCustodyRepository} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        private FakeCustodyRepository() {
            super(null);
        }

        /**
         * 验证 {@code audit} 对应的测试场景，明确输入、预期结果和异常边界。
         */
        @Override
        public void audit(UUID tenantId, String actorType, String actorId, String action,
                          String resourceType, String resourceId, String sourceIp,
                          String detailsJson) {
        }
    }
}
