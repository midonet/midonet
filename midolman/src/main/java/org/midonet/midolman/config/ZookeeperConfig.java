/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.config;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;

/**
 * Possible configuration entries for zookeeper connection information.
 *
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
     * ZooKeeper related configuration: session grace time.
     *
     * The session grace time indicates for how long after we receive a
     * Zookeeper "disconnect" event we should wait for the connection
     * to be re-established. If the connection is not restored during
     * this interval midolman will be shutdown. Note that midolman
     * will also be shutdown if the session expires.
     *
     * Time units are milli-seconds
     *
     * @return zookeeper session grace time
     */
    @ConfigInt(key = "session_gracetime", defaultValue = 30000)
    public int getZooKeeperGraceTime();

    /**
     * ZooKeeper root directory path for Midolman.
     *
     * @return Midolman's zookeeper root directory path
     */
    @ConfigString(key = "midolman_root_key", defaultValue = "/midonet")
    String getMidolmanRootKey();
}
