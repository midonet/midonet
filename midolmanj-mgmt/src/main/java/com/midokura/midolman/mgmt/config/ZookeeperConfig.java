/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;

/**
 * Config interface for Zookeeper
 */
@ConfigGroup(ZookeeperConfig.GROUP_NAME)
public interface ZookeeperConfig {

    String GROUP_NAME = "zookeeper";

    /**
     * ZooKeeper related configuration: the list of hosts in a zookeeper cluster.
     *
     * @return the list as a comma separated string.
     */
    @ConfigString(key = "zookeeper_hosts", defaultValue = "127.0.0.1:2181")
    public String getZooKeeperHosts();

    /**
     * ZooKeeper related configuration: the timeout session.
     *
     * @return the timeout value of the zookeeper connection.
     */
    @ConfigInt(key = "session_timeout", defaultValue = 30000)
    public int getZooKeeperSessionTimeout();

    /**
     * ZooKeeper root directory path for Midolman.
     *
     * @return Midolman's zookeeper root directory path
     */
    @ConfigString(key = "midolman_root_key", defaultValue = "/midonet")
    public String getMidolmanRootKey();
}
