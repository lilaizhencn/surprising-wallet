package com.surprising.wallet.bootstrap;

import org.springframework.context.annotation.Conditional;
import java.lang.annotation.*;

@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Conditional(WalletModeCondition.class)
public @interface WalletModeEnabled { WalletMode[] value(); }
