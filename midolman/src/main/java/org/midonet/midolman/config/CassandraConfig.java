/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.config;

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;

/**
 * Cassandra configuration interface.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/14/12
 */
@ConfigGroup(CassandraConfig.GROUP_NAME)
public interface CassandraConfig {

    public final static String GROUP_NAME = "cassandra";

    @ConfigString(key = "servers", defaultValue = "127.0.0.1:9042")
    public String getCassandraServers();

    @ConfigString(key = "cluster", defaultValue = "midonet")
    public String getCassandraCluster();

    @ConfigString(key = "midonet_keyspace", defaultValue = "midolman")
    public String getCassandraMidonetKeyspace();

    @ConfigInt(key = "replication_factor", defaultValue = 1)
    public int getCassandraReplicationFactor();

    @ConfigInt(key = "max_active_connections", defaultValue=3)
    public int getCassandraMaxActiveConnections();

    @ConfigInt(key = "thrift_socket_timeout", defaultValue=2500)
    public int getCassandraThriftSocketTimeout();

    @ConfigBool(key = "host_timeout_tracker", defaultValue=true)
    public boolean getCassandraHostTimeoutTracker();

    @ConfigInt(key = "host_timeout_counter", defaultValue=10)
    public int getCassandraHostTimeoutCounter();

    @ConfigInt(key = "host_timeout_window", defaultValue=500)
    public int getCassandraHostTimeoutWindow();
}
