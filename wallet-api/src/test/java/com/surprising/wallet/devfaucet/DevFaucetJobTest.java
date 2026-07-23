package com.surprising.wallet.devfaucet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.surprising.wallet.custody.repository.CustodyRepository;
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
import com.surprising.wallet.devfaucet.repository.DevFaucetRepository.Candidate;
import com.surprising.wallet.devfaucet.model.DevFaucetAmountGenerator;
import com.surprising.wallet.devfaucet.model.DevFaucetFunding;
import com.surprising.wallet.devfaucet.job.DevFaucetJob;
import com.surprising.wallet.devfaucet.model.DevFaucetProperties;
import com.surprising.wallet.devfaucet.repository.DevFaucetRepository;
import com.surprising.wallet.devfaucet.service.DevFaucetRpcClient;

class DevFaucetJobTest {
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

    private static DevFaucetRepository.Candidate candidate(
            UUID tenantId, UUID addressId, String chain, String asset,
            String contract, int decimals, String purpose) {
        return new DevFaucetRepository.Candidate(
                tenantId, addressId, chain, "devtest", asset, purpose,
                "0x" + "ab".repeat(20), contract, decimals);
    }

    private static final class FakeRepository extends DevFaucetRepository {
        private List<Candidate> undiscovered;
        private final List<DevFaucetFunding> due = new ArrayList<>();
        private int sent;

        private FakeRepository(List<Candidate> candidates) {
            super(null);
            this.undiscovered = new ArrayList<>(candidates);
        }

        @Override
        public List<Candidate> discover(int limit) {
            List<Candidate> result = List.copyOf(undiscovered);
            undiscovered = List.of();
            return result;
        }

        @Override
        public boolean create(Candidate candidate, BigDecimal amount) {
            due.add(new DevFaucetFunding(
                    UUID.randomUUID(), candidate.tenantId(), candidate.custodyAddressId(),
                    candidate.chain(), candidate.network(), candidate.assetSymbol(),
                    candidate.purpose(), candidate.address(), candidate.contractAddress(),
                    candidate.decimals(), amount, 0));
            return true;
        }

        @Override
        public List<DevFaucetFunding> due(int limit, int maxAttempts) {
            List<DevFaucetFunding> result = List.copyOf(due);
            due.clear();
            return result;
        }

        @Override public boolean markSending(UUID id) { return true; }
        @Override public void markSent(UUID id, String txHash) { sent++; }
        @Override public int recoverStaleSending(Duration age) { return 0; }
        @Override public int reconcileConfirmed() { return 0; }
    }

    private static final class FakeRpcClient implements DevFaucetRpcClient {
        private final List<DevFaucetFunding> sent = new ArrayList<>();

        @Override
        public String send(DevFaucetFunding funding) {
            sent.add(funding);
            return "0x" + String.format("%064x", sent.size());
        }
    }

    private static final class FakeCustodyRepository extends CustodyRepository {
        private FakeCustodyRepository() {
            super(null);
        }

        @Override
        public void audit(UUID tenantId, String actorType, String actorId, String action,
                          String resourceType, String resourceId, String sourceIp,
                          String detailsJson) {
        }
    }
}
