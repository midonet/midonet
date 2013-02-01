/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.monitoring.config;

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;
import org.midonet.midolman.config.CassandraConfig;

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
     * @return Whether monitoring is enabled.
     */
    @ConfigBool(key = "enable_monitoring", defaultValue = true)
    boolean getMidolmanEnableMonitoring();

    /**
     * @return the cassandra key space we want to use for the monitoring data.
     */
    @ConfigString(key = "cassandra_keyspace",
                  defaultValue = "midonet_monitoring")
    String getMonitoringCassandraKeyspace();

    /**
     * @return the cassandra column family we want to use for the monitoring data.
     */
    @ConfigString(key = "cassandra_column_family",
                  defaultValue = "monitoring_data")
    String getMonitoringCassandraColumnFamily();

    /**
     * @return the cassandra expiration timeout (in seconds).
     */
    @ConfigInt(key = "cassandra_expiration_timeout_minutes",
               defaultValue = 365 * 34 * 60)
    int getMonitoringCassandraExpirationTimeout();

    /**
     * @return the pulling time between two metric gathering data entries.
     */
    @ConfigInt(key = "cassandra_reporter_pulling_time",
               defaultValue = 1000)
    int getMonitoringCassandraReporterPullTime();

    /**
     * @return the zookeeper daemon jmx port on the current machine.
     */
    @ConfigInt(key = "zookeeper_jmx_port", defaultValue = -1)
    int getMonitoringZookeeperJMXPort();

    /**
     * @return the milliseconds between port stats requests.
     */
    @ConfigInt(key = "port_stats_request_time", defaultValue=2000)
    int getPortStatsRequestTime();
}
