package com.surprising.wallet.bootstrap;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;
import java.util.Arrays;

public class WalletModeCondition implements Condition {
    @Override public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var attributes = metadata.getAnnotationAttributes(WalletModeEnabled.class.getName());
        return Arrays.asList((WalletMode[]) attributes.get("value")).contains(WalletMode.from(context.getEnvironment()));
    }
}
