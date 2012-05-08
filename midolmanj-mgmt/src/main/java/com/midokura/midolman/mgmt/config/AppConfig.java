/*
 * Copyright 2012 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.config;

import javax.servlet.ServletContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application configuration wrapper class.
 */
public class AppConfig {

    private final static Logger log = LoggerFactory.getLogger(AppConfig.class);
    public final static String defaultDatatStore = "com.midokura.midolman.mgmt.data.zookeeper.ZooKeeperDaoFactory";
    public final static String defaultAuthorizer = "com.midokura.midolman.mgmt.auth.SimpleAuthorizer";
    public final static String defaultZkConnString = "127.0.0.1:2181";
    public final static int defaultZkTimeout = 3000;
    public final static String defaultZkRootPath = "/midonet/midolman";
    public final static String defaultZkMgmtRootPath = "/midonet/midolman-mgmt";

    public final static String versionKey = "version";
    public final static String dataStoreKey = "datastore_service";
    public final static String authorizerKey = "authorizer";
    public final static String zkConnStringKey = "zk_conn_string";
    public final static String zkTimeoutKey = "zk_timeout";
    public final static String zkRootKey = "zk_root";
    public final static String zkMgmtRootKey = "zk_mgmt_root";

    // TODO(rossella) this should be configurable
    public final static String cassandraServer = "localhost:9171";
    public final static String cassandraCluster = "Mido Cluster";
    public final static String cassandraMonitoringKeySpace = "MM_Monitoring";
    public final static String cassandraMonitoringColumnFamily = "Monitoring";
    public final static int cassandraReplicationFactor = 1;
    public final static int cassandraTtlInSecs = 1000;



    private final ServletContext ctx;

    /**
     * Constructor
     *
     * @param ctx
     *            ServletContext object
     */
    public AppConfig(ServletContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @return　The version specified in the config.
     * @throws InvalidConfigException
     *             Version is missing.
     */
    public String getVersion() throws InvalidConfigException {
        log.debug("AppConfig.getVersion entered.");

        String val = ctx.getInitParameter(versionKey);
        if (val == null) {
            throw new InvalidConfigException("Config is missing " + versionKey);
        }

        log.debug("AppConfig.getVersion exiting: {}", val);
        return val;
    }

    /**
     * @return　The data store class name specified in the config.
     */
    public String getDataStoreClassName() {
        log.debug("AppConfig.getDataStoreClassName entered.");

        String val = ctx.getInitParameter(dataStoreKey);
        if (val == null) {
            log.warn(
                    "{} was not specified in the config.  Using the default: {}",
                    dataStoreKey, defaultDatatStore);
            val = defaultDatatStore;
        }

        log.debug("AppConfig.getDataStoreClassName exiting: {}", val);
        return val;
    }

    /**
     * @return　The authorizer class name specified in the config.
     */
    public String getAuthorizerClassName() {
        log.debug("AppConfig.getAuthorizerClassName entered.");

        String val = ctx.getInitParameter(authorizerKey);
        if (val == null) {
            log.warn(
                    "{} was not specified in the config.  Using the default: {}",
                    authorizerKey, defaultAuthorizer);
            val = defaultAuthorizer;
        }

        log.debug("AppConfig.getAuthorizerClassName exiting: {}", val);
        return val;
    }

    /**
     * @return The ZooKeeper connection string specified in the config.
     */
    public String getZkConnectionString() {
        log.debug("AppConfig.getZkConnectionString entered.");

        String val = ctx.getInitParameter(zkConnStringKey);
        if (val == null) {
            log.warn(
                    "{} was not specified in the config.  Using the default: {}",
                    zkConnStringKey, defaultZkConnString);
            val = defaultZkConnString;
        }

        log.debug("AppConfig.getZkConnectionString exiting: {}", val);
        return val;
    }

    /**
     * @return The ZooKeeper timeout specified in the config.
     * @throws InvalidConfigException
     *             Invalid value was specified for timeout.
     */
    public int getZkTimeout() throws InvalidConfigException {
        log.debug("AppConfig.getZkTimeout entered.");

        int valInt = 0;
        String val = ctx.getInitParameter(zkTimeoutKey);
        if (val != null) {
            try {
                valInt = Integer.parseInt(val);
            } catch (NumberFormatException e) {
                throw new InvalidConfigException("Invalid value for "
                        + zkTimeoutKey, e);
            }
        } else {
            log.warn(
                    "{} was not specified in the config.  Using the default: {}",
                    zkTimeoutKey, defaultZkTimeout);
            valInt = defaultZkTimeout;

        }

        log.debug("AppConfig.getZkTimeout exiting: {}", valInt);
        return valInt;
    }

    /**
     * @return The ZooKeeper root path specified in the config.
     */
    public String getZkRootPath() {
        log.debug("AppConfig.getZkRootPath entered.");

        String val = ctx.getInitParameter(zkRootKey);
        if (val == null) {
            log.warn(
                    "{} was not specified in the config.  Using the default: {}",
                    zkRootKey, defaultZkRootPath);
            val = defaultZkRootPath;
        }
        return val;
    }

    /**
     * @return The ZooKeeper root path for management specified in the config.
     */
    public String getZkMgmtRootPath() {
        log.debug("AppConfig.getZkMgmtRootPath entered.");

        String val = ctx.getInitParameter(zkMgmtRootKey);
        if (val == null) {
            log.warn(
                    "{} was not specified in the config.  Using the default: {}",
                    zkMgmtRootKey, defaultZkMgmtRootPath);
            val = defaultZkMgmtRootPath;
        }

        log.debug("AppConfig.getZkMgmtRootPath exiting: {}", val);
        return val;
    }
}
