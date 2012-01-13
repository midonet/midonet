/*
 * @(#)AbstractDaoFactory        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.mgmt.config.AppConfig;

/**
 * Abstract DAO factory class.
 *
 * @version 1.6 15 Nov 2011
 * @author Ryu Ishimoto
 */
public abstract class AbstractDaoFactory implements DaoFactory {

    /**
     * AppConfig object for the factory.
     */
    protected AppConfig config = null;

    /**
     * Constructor
     *
     * @param config
     *            AppConfig object.
     */
    public AbstractDaoFactory(AppConfig config) {
        this.config = config;
    }
}
