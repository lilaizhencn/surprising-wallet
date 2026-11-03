package com.surprising.wallet.custody;

import com.surprising.wallet.exception.CustodyForbiddenException;
import com.surprising.wallet.gateway.WithdrawalApprovalClient;
import com.surprising.wallet.model.CustodyPrincipal;
import com.surprising.wallet.service.CustodyApprovalAuthorityQueryService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustodyApprovalAuthorityQueryServiceTest {
    private final UUID tenant=UUID.randomUUID(), member=UUID.randomUUID();
    private final WithdrawalApprovalClient client=mock(WithdrawalApprovalClient.class);
    private final CustodyApprovalAuthorityQueryService query=new CustodyApprovalAuthorityQueryService(client);
    @Test void tenantAdministratorCanReadEveryAuthorityStatusWithinOwnTenant() {
        var actor=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,member,tenant,"test-tenant","TENANT_ADMIN",Set.of("*"));
        when(client.list(tenant,"",20,0)).thenReturn(new WithdrawalApprovalClient.AuthorityPage(List.of(),0,20,0));
        assertEquals(0,query.list(actor,"",20,0).total());
        verify(client).list(tenant,"",20,0);
        assertThrows(IllegalArgumentException.class,()->query.list(actor,"AUTHORIZED",20,0));
    }
    @Test void nonTenantOrMissingScopeCannotReachAuthority() {
        var platform=new CustodyPrincipal(CustodyPrincipal.ActorType.PLATFORM_USER,member,null,null,"PLATFORM_ADMIN",Set.of("*"));
        var reader=new CustodyPrincipal(CustodyPrincipal.ActorType.TENANT_USER,member,tenant,"test-tenant","VIEWER",Set.of("assets:read"));
        assertThrows(CustodyForbiddenException.class,()->query.list(platform,"",20,0));
        assertThrows(CustodyForbiddenException.class,()->query.list(reader,"",20,0));
        verifyNoInteractions(client);
    }
}
