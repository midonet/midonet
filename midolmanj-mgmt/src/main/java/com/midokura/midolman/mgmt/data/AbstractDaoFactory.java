/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.mgmt.config.AppConfig;

/**
 * Abstract DAO factory class.
 */
public abstract class AbstractDaoFactory implements DaoFactory {

    /**
     * AppConfig object for the factory.
     */
    protected final AppConfig config;

    /**
     * Constructor
     *
     * @param config
     *            AppConfig object.
     */
    public AbstractDaoFactory(AppConfig config) {
        this.config = config;
    }

    protected AppConfig getAppConfig() {
        return config;
    }
}
