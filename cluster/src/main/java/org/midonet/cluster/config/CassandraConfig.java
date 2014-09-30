/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.config;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;

/**
 * Cassandra configuration interface.
 */
@ConfigGroup(CassandraConfig.GROUP_NAME)
public interface CassandraConfig {

    public final static String GROUP_NAME = "cassandra";

    @ConfigString(key = "servers", defaultValue = "127.0.0.1:9042")
    public String getCassandraServers();

    @ConfigString(key = "cluster", defaultValue = "midonet")
    public String getCassandraCluster();

   @ConfigInt(key = "replication_factor", defaultValue = 1)
    public int getCassandraReplicationFactor();
}
