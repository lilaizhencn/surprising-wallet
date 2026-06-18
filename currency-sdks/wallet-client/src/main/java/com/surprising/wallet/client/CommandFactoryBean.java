package com.surprising.wallet.client;

import org.springframework.beans.factory.FactoryBean;

/**
 * @author lilaizhen
 * @data 29/03/2018
 */

public class CommandFactoryBean<T> implements FactoryBean<T> {

    T command;

    public CommandFactoryBean(final T com) {
        this.command = com;
    }

    @Override
    public T getObject() throws Exception {
        return this.command;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<?> getObjectType() {
        return this.command.getClass();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSingleton() {
        return true;
    }
}
