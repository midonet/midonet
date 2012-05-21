/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;
import com.midokura.midolman.config.CassandraConfig;

/**
 * Interface that explains properly all the parameters that we are expecting
 * to be visible to the monitoring system of midolman.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/30/12
 */
@ConfigGroup(MonitoringConfiguration.GROUP_NAME)
public interface MonitoringConfiguration extends CassandraConfig {

    public final static String GROUP_NAME = "monitoring";

    /**
     * @return the cassandra key space we want to use for the monitoring data.
     */
    @ConfigString(key = "cassandra_keyspace",
                  defaultValue = "midonet_monitoring_keyspace")
    String getMonitoringCassandraKeyspace();

    /**
     * @return the cassandra column family we want to use for the monitoring data.
     */
    @ConfigString(key = "cassandra_column_family",
                  defaultValue = "midonet_monitoring_column_family")
    String getMonitoringCassandraColumnFamily();

    /**
     * @return the cassandra replication factor to use.
     */
    @ConfigInt(key = "cassandra_replication_factor", defaultValue = 2)
    int getMonitoringCassandraReplicationFactor();

    /**
     * @return the cassandra expiration timeout (in seconds).
     */
    @ConfigInt(key = "cassandra_expiration_timeout",
               defaultValue = 365 * 34 * 60)
    int getMonitoringCassandraExpirationTimeout();

    /**
     * @return the pooling time between two metric gathering data entries.
     */
    @ConfigInt(key = "cassandra_reporter_pooling_time",
               defaultValue = 1000)
    int getMonitoringCassandraReporterPoolTime();

    /**
     * @return the zookeeper daemon jmx port on the current machine.
     */
    @ConfigInt(key = "zookeeper_jmx_port", defaultValue = -1)
    int getMonitoringZookeeperJMXPort();
}
