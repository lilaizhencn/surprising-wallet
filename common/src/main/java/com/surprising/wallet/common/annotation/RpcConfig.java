package com.surprising.wallet.common.annotation;

import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

import static com.surprising.wallet.common.annotation.RpcConfig.RpcType.JSON_RPC;


/**
 * @author lilaizhen
 * @data 01/04/2018
 */
@Target({ElementType.TYPE})
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RpcConfig {
    @AliasFor("server")
    String value() default "";

    @AliasFor("value")
    String server() default "";

    String username() default "";

    String password() default "";

    RpcType type() default JSON_RPC;

    /**
     * rpc 类型
     */
    enum RpcType {
        JSON_RPC,
        REST_RPC
    }

}
