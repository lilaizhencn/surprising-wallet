package com.surprising.wallet.chain.evm;

import jakarta.annotation.PreDestroy;
import okhttp3.OkHttpClient;
import org.springframework.stereotype.Component;
import org.web3j.protocol.http.HttpService;

import java.util.Map;
import java.util.Objects;

@Component
public final class EvmHttpServiceFactory implements AutoCloseable {
    private final OkHttpClient client;

    public EvmHttpServiceFactory() {
        this(HttpService.getOkHttpClientBuilder().build());
    }

    EvmHttpServiceFactory(OkHttpClient client) {
        this.client = Objects.requireNonNull(client, "client");
    }

    public HttpService create(String rpcUrl) {
        return new HttpService(rpcUrl, client);
    }

    public HttpService create(String rpcUrl, Map<String, String> headers) {
        HttpService service = create(rpcUrl);
        service.addHeaders(headers);
        return service;
    }

    @Override
    @PreDestroy
    public void close() {
        client.connectionPool().evictAll();
        client.dispatcher().executorService().shutdown();
    }
}
