/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.monitoring.config;

/**
 * Interface that explains properly all the parameters that we are expecting
 * to be visible to the monitoring system of midolman.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/30/12
 */
public interface MonitoringConfiguration {

    /**
     * @return the Cassandra servers configuration.
     */
    String getCassandraServers();

    /**
     * @return the cassandra cluster name.
     */
    String getCassandraCluster();

    /**
     * @return the cassandra key space we want to use for the monitoring data.
     */
    String getMonitoringCassandraKeySpace();

    /**
     * @return the cassandra column family we want to use for the monitoring data.
     */
    String getMonitoringCassandraColumnFamily();

    /**
     * @return the cassandra replication factor to use.
     */
    int getCassandraReplicationFactor();

    /**
     * @return the cassandra expiration timeout (in seconds).
     */
    int getCassandraExpirationTimeout();

    /**
     * @return the pooling time between two metric gathering data entries.
     */
    int getMonitoringCassandraReporterPoolTime();

    /**
     * @return the zookeeper daemon jmx port on the current machine.
     */
    int getZookeeperJMXPort();
}
