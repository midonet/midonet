/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.config;

import java.util.concurrent.TimeUnit;

import com.google.inject.Inject;
import com.google.inject.name.Named;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.config.AbstractIniBasedConfiguration;

/**
 * Default implementation of the {@link MonitoringConfiguration} interface.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/30/12
 */
public class DefaultMonitoringConfiguration
    extends AbstractIniBasedConfiguration
    implements MonitoringConfiguration {

    private static final Logger log = LoggerFactory
        .getLogger(DefaultMonitoringConfiguration.class);

    public static final String MONITORING_CONFIG = "MONITORING_CONFIG";

    protected DefaultMonitoringConfiguration(String configFileLocation)
        throws ConfigurationException {
        super(configFileLocation);
    }

    @Inject
    public DefaultMonitoringConfiguration(@Named(MONITORING_CONFIG)
                                          HierarchicalConfiguration config) {
        super(config);
    }

    @Override
    public String getCassandraServers() {
        return safeGetString("cassandra", "servers", "localhost:9160");
    }

    @Override
    public String getCassandraCluster() {
        return safeGetString("cassandra", "cluster", "midolman");
    }

    @Override
    public String getMonitoringCassandraKeyspace() {
        return safeGetString("monitoring",
                             "cassandra_key_space", "midonet_monitoring_keyspace");
    }

    @Override
    public String getMonitoringCassandraColumnFamily() {
        return safeGetString("monitoring", "cassandra_column_family",
                             "midonet_monitoring_column_family");
    }

    @Override
    public int getMonitoringCassandraReplicationFactor() {
        return safeGetInt("cassandra", "replication_factor", 3);
    }

    @Override
    public int getMonitoringCassandraExpirationTimeout() {
        return safeGetInt("cassandra", "expiration_timeout",
                           (int)TimeUnit.DAYS.toSeconds(365)); // 1 year default
    }

    @Override
    public int getMonitoringCassandraReporterPoolTime() {
        return safeGetInt("monitoring", "cassandra_reporter_pool_time",
                          (int)TimeUnit.SECONDS.toMillis(1));
    }

    @Override
    public int getZookeeperJMXPort() {
        return safeGetInt("monitoring", "zookeeper_jmx_port", -1);
    }
}
