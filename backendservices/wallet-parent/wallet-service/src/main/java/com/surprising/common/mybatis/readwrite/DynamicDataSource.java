package com.surprising.common.mybatis.readwrite;

import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.util.Assert;

import java.util.HashMap;
import java.util.Map;

/**
 * 基于Mybatis Plugins方式的读写分离的动态数据源实现
 *
 * @author polarisex
 * @date 2017/12/09
 */
public class DynamicDataSource extends AbstractRoutingDataSource {
    /**
     * 写数据源
     */
    private Object writeDataSource;

    /**
     * 读数据源
     */
    private Object readDataSource;

    @Override
    public void afterPropertiesSet() {
        Assert.notNull(this.writeDataSource, "Property 'writeDataSource' is required");
        this.setDefaultTargetDataSource(this.writeDataSource);
        final Map<Object, Object> targetDataSources = new HashMap<>();
        targetDataSources.put(DataSourceFrom.WRITE.name(), this.writeDataSource);
        if (this.readDataSource != null) {
            targetDataSources.put(DataSourceFrom.READ.name(), this.readDataSource);
        }
        this.setTargetDataSources(targetDataSources);
        super.afterPropertiesSet();
    }

    @Override
    protected Object determineCurrentLookupKey() {
        final DataSourceFrom dataSourceFrom = DynamicDataSourceHolder.getDataSource();
        if (dataSourceFrom != null && dataSourceFrom == DataSourceFrom.READ) {
            return DataSourceFrom.READ.name();
        }
        return DataSourceFrom.WRITE.name();
    }

    public Object getWriteDataSource() {
        return this.writeDataSource;
    }

    public void setWriteDataSource(final Object writeDataSource) {
        this.writeDataSource = writeDataSource;
    }

    public Object getReadDataSource() {
        return this.readDataSource;
    }

    public void setReadDataSource(final Object readDataSource) {
        this.readDataSource = readDataSource;
    }
}
