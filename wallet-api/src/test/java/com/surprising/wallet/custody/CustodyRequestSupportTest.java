package com.surprising.wallet.custody;

import com.surprising.wallet.model.CustodyRequestSupport;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CustodyRequestSupportTest {
    @Test
    void normalizesBracketedCloudflareIpv6ForPostgresInet() {
        assertEquals("2a06:98c0:3600::103",
                CustodyRequestSupport.clientIp(request(" [2a06:98c0:3600::103] ")));
    }

    @Test
    void preservesPlainAddressesAndDoesNotTrustForwardingHeaders() {
        assertEquals("192.0.2.1", CustodyRequestSupport.clientIp(request(" 192.0.2.1 ")));
        assertEquals("2001:db8::1", CustodyRequestSupport.clientIp(request("2001:db8::1")));
        assertEquals("", CustodyRequestSupport.clientIp(request(null)));
    }

    private static HttpServletRequest request(String remoteAddress) {
        return (HttpServletRequest) Proxy.newProxyInstance(
                HttpServletRequest.class.getClassLoader(), new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getRemoteAddr")) {
                        return remoteAddress;
                    }
                    if (method.getName().equals("getHeader")) {
                        return "198.51.100.1";
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
