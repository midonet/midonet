/*
 * @(#)AbstractDaoFactory        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.mgmt.config.AppConfig;

public abstract class AbstractDaoFactory implements DaoFactory {

    protected AppConfig config = null;

    public AbstractDaoFactory(AppConfig config) {
        this.config = config;
    }

    public void initialize() throws DaoInitializationException {
    }
}
