/*
 * @(#)DatastoreSelector        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.mgmt.config.AppConfig;
import com.midokura.midolman.mgmt.config.InvalidConfigException;

public class DatastoreSelector {

    @SuppressWarnings({ "rawtypes" })
    public static DaoFactory getDaoFactory() throws InvalidConfigException {

        AppConfig config = AppConfig.getConfig();
        Class clazz = null;
        try {
            clazz = Class.forName(config.getDataStoreClassName());
        } catch (ClassNotFoundException e) {
            throw new InvalidConfigException(
                    "Could not find class from config", e);
        }

        try {
            return (DaoFactory) clazz.newInstance();
        } catch (InstantiationException e) {
            throw new InvalidConfigException(
                    "Class specified in the config cannot be insantiated", e);
        } catch (IllegalAccessException e) {
            throw new InvalidConfigException(
                    "Class specified in the config is not accessible", e);
        }
    }
}
