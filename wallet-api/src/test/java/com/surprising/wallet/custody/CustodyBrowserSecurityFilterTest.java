package com.surprising.wallet.custody;

import com.surprising.wallet.filter.CustodyBrowserSecurityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CustodyBrowserSecurityFilterTest {
    @Test void pendingVerificationEndpointsEnforceExactOriginAndNeverCacheResponses() throws Exception {
        var filter=new CustodyBrowserSecurityFilter(" https://console.example.test ");
        for(String origin:new String[]{"https://console.example.test","https://console.example.test.attacker.test","https://attacker.test"}) {
            var request=new MockHttpServletRequest("POST","/custody/console/v1/auth/email-code");
            request.addHeader("Origin",origin);
            var response=new MockHttpServletResponse();var reached=new AtomicBoolean();
            filter.doFilter(request,response,(r,s)->reached.set(true));
            assertEquals(origin.equals("https://console.example.test"),reached.get());
            assertEquals("no-store",response.getHeader("Cache-Control"));
        }
        var request=new MockHttpServletRequest("POST","/custody/console/v1/account-security/totp/confirm");
        request.addHeader("Sec-Fetch-Site","cross-site");
        var response=new MockHttpServletResponse();filter.doFilter(request,response,(r,s)->fail("Cross-site request accepted"));
        assertEquals(403,response.getStatus());
    }
}
