package com.surprising.common.mybatis.readwrite;

import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;

import javax.sql.DataSource;

/**
 * @author polarisex
 * @date 2017/12/09
 */
public class DynamicDataSourceTransactionManager extends DataSourceTransactionManager {
    /**
     *
     */
    public DynamicDataSourceTransactionManager() {
        super();
    }

    /**
     * @param dataSource
     */
    public DynamicDataSourceTransactionManager(final DataSource dataSource) {
        super(dataSource);
    }

    /**
     * 只读事务到读库，读写事务到写库
     *
     * @param transaction
     * @param definition
     */
    @Override
    protected void doBegin(final Object transaction, final TransactionDefinition definition) {
        //设置数据源
        final boolean readOnly = definition.isReadOnly();
        if (readOnly) {
            DynamicDataSourceHolder.putDataSource(DataSourceFrom.READ);
        } else {
            DynamicDataSourceHolder.putDataSource(DataSourceFrom.WRITE);
        }
        super.doBegin(transaction, definition);
    }

    /**
     * 清理本地线程的数据源
     *
     * @param transaction
     */
    @Override
    protected void doCleanupAfterCompletion(final Object transaction) {
        super.doCleanupAfterCompletion(transaction);
        DynamicDataSourceHolder.clearDataSource();
    }
}
