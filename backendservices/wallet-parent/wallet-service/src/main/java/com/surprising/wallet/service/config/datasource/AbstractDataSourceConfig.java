package com.surprising.wallet.service.config.datasource;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * @author atomex
 */
@Slf4j
public class AbstractDataSourceConfig {

    protected DataSourceTransactionManager createTransactionManager(DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }

    protected SqlSessionFactory createSqlSessionFactory(DataSource dataSource, String mapperLocation)
            throws Exception {
        return createSqlSessionFactory(dataSource, new String[]{mapperLocation});
    }

    protected SqlSessionFactory createSqlSessionFactory(DataSource dataSource, String[] mapperLocations)
            throws Exception {
        return createSqlSessionFactoryBean(dataSource, mapperLocations).getObject();
    }

    protected SqlSessionFactoryBean createSqlSessionFactoryBean(DataSource dataSource,
                                                                String mapperLocation) {
        return createSqlSessionFactoryBean(dataSource, new String[]{mapperLocation});
    }

    protected SqlSessionFactoryBean createSqlSessionFactoryBean(DataSource dataSource,
                                                                String[] mapperLocations) {
        SqlSessionFactoryBean sessionFactory = new SqlSessionFactoryBean();
        sessionFactory.setDataSource(dataSource);
        Configuration configuration = new Configuration();
        configuration.setCacheEnabled(true);
        configuration.setLazyLoadingEnabled(true);
        configuration.setMultipleResultSetsEnabled(true);
        configuration.setUseColumnLabel(true);
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultExecutorType(ExecutorType.REUSE);
        configuration.setDefaultStatementTimeout(25000);
        sessionFactory.setConfiguration(configuration);
        sessionFactory.setMapperLocations(resolveMapperLocations(mapperLocations));
        return sessionFactory;
    }

    protected SqlSessionTemplate createSqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    protected TransactionTemplate createTransactionTemplate(DataSourceTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    protected Resource[] resolveMapperLocations(String[] mapperLocations) {
        ResourcePatternResolver resourceResolver = new PathMatchingResourcePatternResolver();
        List<Resource> resources = new ArrayList<>();
        if (mapperLocations != null) {
            Arrays.stream(mapperLocations).forEach(mapperLocation -> {
                try {
                    Resource[] mappers = resourceResolver.getResources(mapperLocation);
                    resources.addAll(Arrays.asList(mappers));
                } catch (IOException e) {
                    // ignore
                    log.warn("mybatis resolve mapper locations error={}", e.getMessage());
                }
            });
        }
        return resources.toArray(new Resource[0]);
    }
}