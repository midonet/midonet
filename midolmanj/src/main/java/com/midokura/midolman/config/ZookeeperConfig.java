/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;

/**
 * Possible configuration entries for zookeeper connection information.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/14/12
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

}
