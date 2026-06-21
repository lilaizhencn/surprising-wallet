package com.surprising.wallet.client;

import com.alibaba.fastjson.support.retrofit.Retrofit2ConverterFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.googlecode.jsonrpc4j.JsonRpcHttpClient;
import com.googlecode.jsonrpc4j.ProxyUtil;
import com.surprising.wallet.common.annotation.RpcConfig;
import lombok.extern.log4j.Log4j2;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.lang3.ClassUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import retrofit2.Call;
import retrofit2.CallAdapter;
import retrofit2.Retrofit;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.googlecode.jsonrpc4j.JsonRpcBasicServer.ERROR;
import static com.surprising.wallet.common.annotation.RpcConfig.RpcType.JSON_RPC;
import static com.surprising.wallet.common.annotation.RpcConfig.RpcType.REST_RPC;

/**
 * @author lilaizhen
 * @data 29/03/2018
 */
@Component
@Log4j2
public class RpcCommandProcessor implements BeanDefinitionRegistryPostProcessor, EnvironmentAware {

    private final static String RPC_LOCATION = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + "com/surprising/wallet/client/command/**/*.class";


    private Environment environment;
    private BeanDefinitionRegistry registry;

    private final int connectionTimeoutMillis = 120 * 1000;
    private final int readTimeoutMillis = 120 * 1000;

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        this.registry = registry;
    }


    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
        try {
            ResourcePatternResolver resourcePatternResolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resourcePatternResolver.getResources(RPC_LOCATION);
            MetadataReaderFactory metadataReaderFactory = new CachingMetadataReaderFactory(resourcePatternResolver);
            for (Resource e : resources) {
                MetadataReader metadataReader = metadataReaderFactory.getMetadataReader(e);
                Class<?> command = ClassUtils.getClass(metadataReader.getClassMetadata().getClassName());
                if (command.isInterface()) {
                    RpcConfig config = command.getAnnotation(RpcConfig.class);
                    if (!ObjectUtils.isEmpty(config)) {
                        String server = getProperty(config.server());
                        if (!StringUtils.hasText(server)) {
                            continue;
                        }
                        String username = getProperty(config.username());
                        String password = getProperty(config.password());
                        Object obj = null;
                        if (config.type() == JSON_RPC) {
                            JsonRpcHttpClient client = buildRpcClient(command, server, username, password);
                            obj = ProxyUtil.createClientProxy(getClass().getClassLoader(), command, client);
                        } else if (config.type() == REST_RPC) {
                            obj = buildRestClient(command, server, username, password);
                        } else {
                            throw new RuntimeException("Not support rpc type: " + config.type().name());
                        }

                        GenericBeanDefinition definition = new GenericBeanDefinition();
                        definition.getConstructorArgumentValues().addGenericArgumentValue(obj);
                        definition.setBeanClass(CommandFactoryBean.class);
                        registry.registerBeanDefinition(command.getSimpleName(), definition);

                    }
                }
            }

        } catch (Throwable e) {
            log.error("scan rpc command error", e);
            throw new BeanCreationException("scan rpc command error");
        }


    }

    private <T> T buildRestClient(Class<T> command, String server, String username, String password) {

        Map<String, String> authorization = new HashMap<>(2);
        authorization.put("Content-type", "application/json");
        String auth = username + ":" + password;

        OkHttpClient client = new OkHttpClient.Builder()
                .connectTimeout(connectionTimeoutMillis, TimeUnit.MILLISECONDS)
                .readTimeout(readTimeoutMillis, TimeUnit.MILLISECONDS)
                .addInterceptor((chain) -> {
                    Request.Builder builder = chain.request()
                            .newBuilder()
                            .addHeader("Content-Type", "application/json")
                            .addHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(auth.getBytes()));

                    Request request = builder.build();
                    return chain.proceed(request);
                })
                .build();

        Retrofit.Builder builder = new Retrofit.Builder()
                .baseUrl(server)
                .addConverterFactory(new Retrofit2ConverterFactory())
                .addCallAdapterFactory(new RestCallAdapterFactory())
                .client(client);
        builder.baseUrl(server);

        Retrofit retrofit = builder.build();
        T obj = retrofit.create(command);
        return obj;

    }

    class RestCallAdapterFactory extends CallAdapter.Factory {

        @Override
        public CallAdapter<?, ?> get(Type returnType, Annotation[] annotations, Retrofit retrofit) {
            return new RestCallAdapter(returnType);
        }
    }

    class RestCallAdapter<R> implements CallAdapter<R, Object> {
        private final Type responseType;

        RestCallAdapter(Type responseType) {
            this.responseType = responseType;
        }

        @Override
        public Type responseType() {
            return responseType;
        }

        @Override
        public Object adapt(Call<R> call) {
            try {
                return call.execute().body();
            } catch (Throwable e) {
                log.error("Got an error:", e);
                throw new RuntimeException(e);
            }
        }
    }

    private JsonRpcHttpClient buildRpcClient(Class command, String server, String username, String password) {
        Map<String, String> authorization = new HashMap<>(3);
        authorization.put("Content-type", "application/json");
        // Only send Basic Auth when username is non-empty.
        // Empty credentials means the RPC endpoint uses URL-based API key (e.g. Alchemy/Infura).
        if (username != null && !username.isEmpty()) {
            String auth = username + ":" + (password != null ? password : "");
            authorization.put(
                    "Authorization",
                    "Basic " + java.util.Base64.getEncoder().encodeToString(auth.getBytes()));
        }

        JsonRpcHttpClient client = null;
        try {
            client = new JsonRpcHttpClient(new URL(server), authorization) {
                @Override
                protected boolean hasError(ObjectNode jsonObject) {
                    if (jsonObject.has(ERROR) && jsonObject.get(ERROR) != null && !jsonObject.get(ERROR).isNull()) {
                        JsonNode node = jsonObject.get(ERROR);
                        if (node instanceof IntNode && node.intValue() == 0) {
                            return false;
                        } else {
                            return true;
                        }
                    } else {
                        return false;
                    }
                }
            };
        } catch (Exception e) {
            log.error("command:{} buildRpcClient error server:{} username:{} password:{}", command.getSimpleName(), server, username, password);
            e.printStackTrace();
        }
        client.setConnectionTimeoutMillis(connectionTimeoutMillis);
        client.setReadTimeoutMillis(readTimeoutMillis);
        return client;
    }


    private String getProperty(String key) {
        key = key.replaceAll("(\\$|\\{|\\})", "");
        String value = environment.getProperty(key);
        if (!StringUtils.hasText(value)) {
            value = "";
        }
        return value;
    }

    @Override
    public void setEnvironment(Environment env) {
        environment = env;
    }

}
