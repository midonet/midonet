/*
 * @(#)AppConfig        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.config;

import javax.servlet.ServletContext;

public class AppConfig {

    private final static String versionKey = "version";
    private final static String dataStoreKey = "datastore_service";
    private final static String zkConnStringKey = "zk_conn_string";
    private final static String zkTimeoutKey = "zk_timeout";
    private final static String zkRootKey = "zk_root";
    private final static String zkMgmtRootKey = "zk_mgmt_root";

    private static AppConfig config = null;
    private ServletContext ctx = null;

    private AppConfig(ServletContext ctx) {
        this.ctx = ctx;
    }

    synchronized public static void init(ServletContext c) {
        // This can only be called once. Throw an exception if already set.
        if (config == null) {
            config = new AppConfig(c);
        } else {
            throw new UnsupportedOperationException(
                    "Config has already been initialized.");
        }
    }

    public static AppConfig getConfig() {
        return config;
    }

    public String getVersion() throws InvalidConfigException {
        String val = ctx.getInitParameter(versionKey);
        if (val == null) {
            throw new InvalidConfigException("Config is missing " + versionKey);
        }
        return val;
    }

    public String getDataStoreClassName() throws InvalidConfigException {
        String val = ctx.getInitParameter(dataStoreKey);
        if (val == null) {
            throw new InvalidConfigException("Config is missing "
                    + dataStoreKey);
        }
        return val;
    }

    public String getZkConnectionString() throws InvalidConfigException {
        String val = ctx.getInitParameter(zkConnStringKey);
        if (val == null) {
            throw new InvalidConfigException("Config is missing "
                    + zkConnStringKey);
        }
        return val;
    }

    public int getZkTimeout() throws InvalidConfigException {
        String val = ctx.getInitParameter(zkTimeoutKey);
        if (val == null) {
            throw new InvalidConfigException("Config is missing "
                    + zkTimeoutKey);
        }
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            throw new InvalidConfigException("Invalid value for "
                    + zkTimeoutKey, e);
        }
    }

    public String getZkRootPath() throws InvalidConfigException {
        String val = ctx.getInitParameter(zkRootKey);
        if (val == null) {
            throw new InvalidConfigException("Config is missing " + zkRootKey);
        }
        return val;
    }

    public String getZkMgmtRootPath() throws InvalidConfigException {
        String val = ctx.getInitParameter(zkMgmtRootKey);
        if (val == null) {
            throw new InvalidConfigException("Config is missing "
                    + zkMgmtRootKey);
        }
        return val;
    }

}
