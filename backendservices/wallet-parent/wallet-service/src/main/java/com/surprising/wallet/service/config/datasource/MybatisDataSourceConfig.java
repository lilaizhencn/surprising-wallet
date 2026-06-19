package com.surprising.wallet.service.config.datasource;

import com.surprising.common.mybatis.readwrite.DynamicDataSourcePlugin;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

import static com.surprising.wallet.service.config.datasource.MybatisDataSourceConfig.WALLET_DAO;


/**
 * @author atomex
 * @data 26/03/2018
 */
@Configuration
@MapperScan(basePackages = {WALLET_DAO}, sqlSessionFactoryRef = "sqlSessionFactory")
public class MybatisDataSourceConfig extends AbstractDataSourceConfig {
    static final String WALLET_DAO = "com.surprising.wallet.service.dao";

    private static final String[] MAPPER_LOCATIONS = {
            "classpath*:mapper/*.xml"
    };

    @Bean(name = "sqlSessionFactory")
    public SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
        SqlSessionFactoryBean sessionFactory = createSqlSessionFactoryBean(dataSource, MybatisDataSourceConfig.MAPPER_LOCATIONS);
        sessionFactory.setPlugins(new DynamicDataSourcePlugin());
        return sessionFactory.getObject();
    }

    @Bean(name = "sqlSessionTemplate")
    public SqlSessionTemplate mybatisSqlSessionTemplate(@Qualifier("sqlSessionFactory")
                                                                SqlSessionFactory sqlSessionFactory) {
        return createSqlSessionTemplate(sqlSessionFactory);
    }
}
