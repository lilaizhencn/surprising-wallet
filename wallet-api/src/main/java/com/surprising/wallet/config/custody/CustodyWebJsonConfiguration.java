package com.surprising.wallet.config.custody;

import java.math.BigDecimal;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializerBase;

/** HTTP money is decimal text; chain RPC serialization retains its own numeric protocol. */
@Configuration(proxyBeanMethods=false)
public class CustodyWebJsonConfiguration implements WebMvcConfigurer {
    @Override
    public void configureMessageConverters(HttpMessageConverters.ServerBuilder builder) {
        var money=new SimpleModule("custody-http-decimal-amounts");
        money.addSerializer(BigDecimal.class,new ToStringSerializerBase(BigDecimal.class) {
            @Override public String valueToString(Object value) { return ((BigDecimal)value).toPlainString(); }
        });
        builder.configureMessageConvertersList(converters -> converters.replaceAll(converter -> converter instanceof JacksonJsonHttpMessageConverter json
                ? new JacksonJsonHttpMessageConverter(json.getMapper().rebuild().addModule(money)
                    .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build()) : converter));
    }
}
