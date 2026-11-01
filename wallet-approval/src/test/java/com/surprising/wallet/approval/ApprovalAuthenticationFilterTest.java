package com.surprising.wallet.approval;

import com.surprising.wallet.approval.config.ApprovalProperties;
import com.surprising.wallet.approval.filter.ApprovalAuthenticationFilter;
import com.surprising.wallet.approval.model.ApprovalPrincipal;
import com.surprising.wallet.approval.service.ApprovalAuthenticationService;
import com.surprising.wallet.common.approval.SigningAuthorization;
import jakarta.servlet.http.Cookie;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApprovalAuthenticationFilterTest {
    private final ApprovalAuthenticationService auth=mock(ApprovalAuthenticationService.class);
    private final UUID tenant=UUID.randomUUID(),member=UUID.randomUUID();
    private ApprovalAuthenticationFilter filter(Map<String,Set<UUID>> clients) {
        return new ApprovalAuthenticationFilter(auth,new ApprovalProperties("https://approval.example",clients),new ObjectMapper());
    }
    private MockHttpServletRequest request(String method,String path) {
        var request=new MockHttpServletRequest(method,path);request.setSecure(true);return request;
    }
    private boolean allowed(ApprovalAuthenticationFilter filter,MockHttpServletRequest request) throws Exception {
        var passed=new AtomicBoolean();var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(r,s)->passed.set(true));
        if(!passed.get()) assertTrue(response.getStatus()==401 || response.getStatus()==404);
        assertEquals("no-store",response.getHeader("Cache-Control"));return passed.get();
    }
    @Test void browserMutationsRequireHttpsExactIndependentOriginAndCsrf() throws Exception {
        var principal=new ApprovalPrincipal(tenant,member,"APPROVER","reviewer@example.test","Reviewer","a".repeat(64));
        when(auth.require("as_token")).thenReturn(principal);
        var filter=filter(Map.of());
        var request=request("POST","/approval/v1/requests/one/decisions");
        request.setCookies(new Cookie(ApprovalAuthenticationFilter.COOKIE,"as_token"));
        request.addHeader("X-Approval-CSRF",principal.csrfToken());
        assertFalse(allowed(filter,request));
        request.addHeader("Origin","https://wallet.example");assertFalse(allowed(filter,request));
        request.removeHeader("Origin");request.addHeader("Origin","https://approval.example");
        assertTrue(allowed(filter,request));
        request.removeHeader("X-Approval-CSRF");assertFalse(allowed(filter,request));
        request.addHeader("X-Approval-CSRF",principal.csrfToken());request.setSecure(false);assertFalse(allowed(filter,request));
    }
    @Test void loginIsIndependentAndDoesNotAcceptAnUntrustedOrigin() throws Exception {
        var filter=filter(Map.of());var login=request("POST","/approval/v1/auth/login");
        login.addHeader("Origin","https://wallet.example");assertFalse(allowed(filter,login));
        login.removeHeader("Origin");login.addHeader("Origin","https://approval.example");assertTrue(allowed(filter,login));
        verifyNoInteractions(auth);
        var session=request("GET","/approval/v1/auth/session");
        session.setCookies(new Cookie("cs_session","wallet-token"));
        when(auth.require(null)).thenThrow(new SecurityException("approval login required"));
        assertFalse(allowed(filter,session));
    }
    @Test void activationRequiresExactIndependentOriginWithoutAcceptingWalletOrServiceAuthentication() throws Exception {
        var filter=filter(Map.of());var activation=request("POST","/approval/v1/auth/activate");
        activation.addHeader("Origin","https://wallet.example");assertFalse(allowed(filter,activation));
        activation.removeHeader("Origin");activation.addHeader("Origin","https://approval.example");assertTrue(allowed(filter,activation));
        verifyNoInteractions(auth);
        when(auth.require(null)).thenThrow(new SecurityException("independent login required"));
        var management=request("POST","/approval/v1/members");management.addHeader("Origin","https://approval.example");
        assertFalse(allowed(filter,management));
    }
    @Test void serviceRequestsRequireActualEnrolledCertificateAndCannotSpoofTenant() throws Exception {
        var pub=KeyPairGenerator.getInstance("EC").generateKeyPair().getPublic();
        var cert=mock(X509Certificate.class);when(cert.getPublicKey()).thenReturn(pub);
        var identity=SigningAuthorization.hash(pub.getEncoded());
        var filter=filter(Map.of(identity,Set.of(tenant)));
        var request=request("POST","/approval/service/v1/intents");
        request.addHeader("X-Client-SPKI",identity);assertFalse(allowed(filter,request));
        request.setAttribute("jakarta.servlet.request.X509Certificate",new X509Certificate[]{cert});
        assertTrue(allowed(filter,request));
        var client=(ApprovalAuthenticationFilter.ServiceClient)request.getAttribute(ApprovalAuthenticationFilter.CLIENT);
        assertDoesNotThrow(()->client.requireTenant(tenant));assertThrows(SecurityException.class,()->client.requireTenant(UUID.randomUUID()));
        assertFalse(allowed(filter(Map.of()),request));
    }
}
