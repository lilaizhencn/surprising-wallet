package com.surprising.wallet.custody;

import com.surprising.wallet.config.custody.CustodyWebJsonConfiguration;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class CustodyMoneyHttpTest {
    @Configuration(proxyBeanMethods=false) @EnableWebMvc static class Web { }
    @RestController static class Money {
        @GetMapping("/money") Map<String,Object> values() {
            return Map.of("amount",new BigDecimal("9007199254740993.000000000000000001"),"tiny",new BigDecimal("1E-36"));
        }
    }
    @Test void httpMoneyRetainsPrecisionWithoutChangingChainRpcNumbers() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Web.class,CustodyWebJsonConfiguration.class,Money.class);context.refresh();
            String json=MockMvcBuilders.webAppContextSetup(context).build().perform(get("/money")).andReturn().getResponse().getContentAsString();
            var parsed=new tools.jackson.databind.ObjectMapper().readTree(json);
            assertTrue(parsed.get("amount").isString());assertEquals("9007199254740993.000000000000000001",parsed.get("amount").asString());
            assertTrue(parsed.get("tiny").isString());assertEquals("0."+"0".repeat(35)+"1",parsed.get("tiny").asString());
            var rpcMapper=new com.surprising.wallet.config.custody.CustodyJacksonConfiguration().custodyObjectMapper();
            assertTrue(rpcMapper.readTree(rpcMapper.writeValueAsString(Map.of("amount",new BigDecimal("1.25")))).get("amount").isNumber());
        }
    }
}
