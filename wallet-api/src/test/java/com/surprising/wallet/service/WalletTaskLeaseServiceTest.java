package com.surprising.wallet.service;

import com.surprising.wallet.repository.WalletTaskLeaseRepository;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WalletTaskLeaseServiceTest {
    @Test void simultaneousInvocationsInOneJvmCannotShareTheirLeaseOwner() throws Exception {
        var repository=mock(WalletTaskLeaseRepository.class);var owner=new AtomicReference<String>();
        when(repository.acquire(eq("xrp-preparation"),anyString(),any())).thenAnswer(call->{
            String candidate=call.getArgument(1);return owner.compareAndSet(null,candidate)||owner.get().equals(candidate);
        });
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        var service=new WalletTaskLeaseService(repository);
        try(var executor=Executors.newSingleThreadExecutor()) {
            var first=executor.submit(()->{try{return service.execute("xrp-preparation",()->{entered.countDown();release.await(5,TimeUnit.SECONDS);return "done";});}
                catch(Throwable error){throw new IllegalStateException(error);}});
            assertTrue(entered.await(5,TimeUnit.SECONDS));
            try {assertNull(service.execute("xrp-preparation",()->fail("same-JVM lease reentered")));}
            catch(Throwable error){throw new IllegalStateException(error);}
            finally {release.countDown();}
            assertEquals("done",first.get(5,TimeUnit.SECONDS));
        } finally {service.shutdown();}
        verify(repository,times(1)).release(eq("xrp-preparation"),anyString());
    }
}
