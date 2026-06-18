package com.surprising.common.mybatis.readwrite;

import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.keygen.SelectKeyGenerator;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author polarisex
 * @date 2017/12/09
 */
@Slf4j
@Intercepts({
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class DynamicDataSourcePlugin implements Interceptor {
    private static final Map<String, DataSourceFrom> CACHE_MAP = new ConcurrentHashMap<>();

    @Override
    public Object intercept(final Invocation invocation) throws Throwable {
        final boolean synchronizationActive = TransactionSynchronizationManager.isSynchronizationActive();
        if (!synchronizationActive) {
            final Object[] objects = invocation.getArgs();
            final MappedStatement mappedStatement = (MappedStatement) objects[0];
            final DataSourceFrom dynamicDataSourceType = this.getDynamicDataSourceType(mappedStatement);
            CACHE_MAP.put(mappedStatement.getId(), dynamicDataSourceType);
            DynamicDataSourceHolder.putDataSource(dynamicDataSourceType);

            log.debug("SqlMapper ID: [{}] From DataSource Type: [{}],SqlCommandType: [{}]",
                    mappedStatement.getId(), dynamicDataSourceType.name(), mappedStatement.getSqlCommandType().name());
        }
        return invocation.proceed();
    }

    private DataSourceFrom getDynamicDataSourceType(final MappedStatement mappedStatement) {
        final DataSourceFrom dynamicDataSourceType = CACHE_MAP.get(mappedStatement.getId());
        if (dynamicDataSourceType != null) {
            return dynamicDataSourceType;
        }
        //如是不是select语句则使用主库
        if (!SqlCommandType.SELECT.equals(mappedStatement.getSqlCommandType())) {
            return DataSourceFrom.WRITE;
        }
        //selectKey为自增id查询主键(SELECT LAST_INSERT_ID())方法,使用主库
        if (mappedStatement.getId().contains(SelectKeyGenerator.SELECT_KEY_SUFFIX)) {
            return DataSourceFrom.WRITE;
        }
        return DataSourceFrom.READ;
    }

    @Override
    public Object plugin(final Object target) {
        if (target instanceof Executor) {
            return Plugin.wrap(target, this);
        }
        return target;
    }

    @Override
    public void setProperties(final Properties properties) {
    }
}
