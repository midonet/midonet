package com.midokura.midolman.mgmt.config;

import javax.naming.InitialContext;
import javax.naming.NamingException;

public class AppConfig {

    private final static String dataStoreEnvPath = "java:comp/env/datastore_service";
    private final static String connectionStringEnvPath = "java:comp/env/connection_string";
    private final static String timeoutEnvPath = "java:comp/env/timeout";
    private final static String zkRootPathEnvPath = "java:comp/env/zk_root_path";
    private final static String zkMgmtRootPathEnvPath = "java:comp/env/zk_mgmt_root_path";

    private InitialContext ctx = null;
    private static AppConfig config = null;

    private AppConfig(InitialContext ctx) {
        this.ctx = ctx;
    }

    synchronized public static AppConfig getConfig()
            throws InvalidConfigException {
        if (null == config) {
            try {
                config = new AppConfig(new InitialContext());
            } catch (NamingException e) {
                throw new InvalidConfigException("Could not initialize config",
                        e);
            }
        }
        return config;
    }

    public String getDataStoreClassName() throws InvalidConfigException {
        try {
            return ctx.lookup(dataStoreEnvPath).toString();
        } catch (NamingException e) {
            throw new InvalidConfigException("Config is missing "
                    + dataStoreEnvPath, e);
        }
    }

    public String getConnectionString() throws InvalidConfigException {
        try {
            return ctx.lookup(connectionStringEnvPath).toString();
        } catch (NamingException e) {
            throw new InvalidConfigException("Config is missing "
                    + connectionStringEnvPath, e);
        }
    }

    public int getTimeout() throws InvalidConfigException {
        try {
            return (Integer) ctx.lookup(timeoutEnvPath);
        } catch (NamingException e) {
            throw new InvalidConfigException("Config is missing "
                    + timeoutEnvPath, e);
        }
    }

    public String getZkRootPathEnvPath() throws InvalidConfigException {
        try {
            return ctx.lookup(zkRootPathEnvPath).toString();
        } catch (NamingException e) {
            throw new InvalidConfigException("Config is missing "
                    + zkRootPathEnvPath, e);
        }
    }

    public String getZkMgmtRootPathEnvPath() throws InvalidConfigException {
        try {
            return ctx.lookup(zkMgmtRootPathEnvPath).toString();
        } catch (NamingException e) {
            throw new InvalidConfigException("Config is missing "
                    + zkMgmtRootPathEnvPath, e);
        }
    }

}
