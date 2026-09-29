package com.surprising.wallet.bootstrap;

import com.surprising.wallet.common.key.WalletKeyMaterialProvider;
import com.surprising.wallet.common.key.WalletSeedCodec;
import com.surprising.wallet.common.queue.QueueWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import tools.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WalletModeTest {
    static String seed(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }
    static String publicRoot(int value) {
        return WalletSeedCodec.bip32Root("test", seed(value)).pubSerialize(0, false);
    }
    static Map<String, Object> properties(String mode) {
        var props = new HashMap<String, Object>();
        props.put("sw.wallet.mode", mode);
        if (mode.equals("all") || mode.equals("sig1")) props.put("sw.wallet.keys.sig1-seed", seed(1));
        if (!mode.equals("sig1")) props.put("sw.wallet.keys.sig2-seed", seed(2));
        if (mode.equals("all") || mode.equals("api")) props.put("sw.wallet.keys.ed25519-seed", seed(4));
        if (!mode.equals("sig2")) props.put("sw.wallet.keys.recovery-public-root", publicRoot(3));
        if (mode.equals("sig1")) props.put("sw.wallet.keys.sig2-public-root", publicRoot(2));
        if (mode.equals("api")) props.put("sw.wallet.keys.sig1-public-root", publicRoot(1));
        return props;
    }
    @Test void modesLoadOnlyTheirKeysAndSignerComponents() {
        for (String mode : new String[]{"all", "api", "sig1", "sig2"}) {
            try (var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties(mode)));
                context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
                context.registerBean(QueueWorker.class, () -> mock(QueueWorker.class));
                context.register(RuntimeKeyConfiguration.class, FirstSignerComponents.class, SecondSignerComponents.class);
                context.refresh();
                boolean first = mode.equals("all") || mode.equals("sig1");
                boolean second = mode.equals("all") || mode.equals("sig2");
                boolean api = mode.equals("all") || mode.equals("api");
                assertEquals(first, context.containsBean("sig1KeyMaterial"), mode);
                assertEquals(second, context.containsBean("sig2KeyMaterial"), mode);
                assertEquals(api, context.containsBean("apiKeyMaterial"), mode);
                assertEquals(first ? 1 : 0, context.getBeansOfType(com.surprising.wallet.sig.first.SignContent.class).size());
                assertEquals(second ? 1 : 0, context.getBeansOfType(com.surprising.wallet.sig.second.SignContent.class).size());
                if (first) {
                    var keys = context.getBean("sig1KeyMaterial", WalletKeyMaterialProvider.class);
                    assertThrows(IllegalStateException.class, keys::sig2Root);
                    assertThrows(IllegalStateException.class, keys::ed25519);
                    assertEquals(publicRoot(1), keys.sig1Root().pubSerialize(0, false));
                    assertEquals("sig1-scheduler-", context.getBean("sig1TaskScheduler", ThreadPoolTaskScheduler.class).getThreadNamePrefix());
                }
                if (second) {
                    var keys = context.getBean("sig2KeyMaterial", WalletKeyMaterialProvider.class);
                    assertThrows(IllegalStateException.class, keys::sig1Root);
                    assertThrows(IllegalStateException.class, keys::ed25519);
                    assertEquals(publicRoot(2), keys.sig2Root().pubSerialize(0, false));
                }
                if (api) {
                    var keys = context.getBean(WalletKeyMaterialProvider.class);
                    assertThrows(IllegalStateException.class, keys::sig1Root);
                    assertEquals(publicRoot(1), keys.sig1PublicRoot().pubSerialize(0, false));
                    assertNotNull(keys.ed25519());
                }
            }
        }
    }
    @Test void signerEnvironmentEnforcesHeadlessModeAndOwnDatabaseRole() {
        for (String mode : new String[]{"sig1", "sig2"}) {
            var env = new StandardEnvironment();
            env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
                    "sw.wallet.mode", mode, "spring.main.web-application-type", "servlet",
                    "spring.datasource.username", "api_user", "sw.wallet." + mode + ".db-username", "isolated")));
            new WalletModeEnvironment().postProcessEnvironment(env, new SpringApplication());
            assertEquals("none", env.getProperty("spring.main.web-application-type"));
            assertEquals("isolated", env.getProperty("spring.datasource.username"));
        }
    }
    @Test void invalidModeFailsBeforeStarting() {
        var env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of("sw.wallet.mode", "typo")));
        assertThrows(IllegalArgumentException.class, () -> new WalletModeEnvironment().postProcessEnvironment(env, new SpringApplication()));
    }
    @Test void rejectsPrivateKeyInPublicConfigurationAndDuplicateMultisigKeys() {
        String privateRoot = WalletSeedCodec.bip32Root("test", seed(2)).privSerialize(0, false);
        assertThrows(IllegalArgumentException.class, () -> WalletKeyMaterialProvider.forSig1(seed(1), privateRoot, publicRoot(3)));
        assertThrows(IllegalArgumentException.class, () -> WalletKeyMaterialProvider.forSig1(seed(1), publicRoot(1), publicRoot(3)));
        assertThrows(IllegalArgumentException.class, () -> WalletKeyMaterialProvider.forApi(seed(2), seed(1), publicRoot(1), publicRoot(3)));
    }
}
