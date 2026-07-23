package com.surprising.wallet.common.annotation;

import java.lang.annotation.*;

/**
 * @author lilaizhen
 * @data 01/04/2018
 */
@Target({ElementType.TYPE})
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface StartThread {
    String value() default "";
}
