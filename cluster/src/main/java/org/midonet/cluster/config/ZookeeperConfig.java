/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.config;

import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;

/**
 * Zookeeper cluster configuration parameters.
 */
@ConfigGroup(ZookeeperConfig.GROUP_NAME)
public interface ZookeeperConfig {

    public static final String GROUP_NAME = "zookeeper";
    public static final int DEFAULT_TIMEOUT_MS = 30000;
    public static final int DEFAULT_GRACETIME_MS = 30000;
    public static final String DEFAULT_HOSTS = "127.0.0.1:2181";

    /**
     * Comma-separated string containing a host:port per zk node.
     */
    @ConfigString(key = "zookeeper_hosts", defaultValue = DEFAULT_HOSTS)
    public String getZkHosts();

    /**
     * The timeout value of the zookeeper session, in millis.
     */
    @ConfigInt(key = "session_timeout", defaultValue = DEFAULT_TIMEOUT_MS)
    public int getZkSessionTimeout();

    /**
     * The session grace time (in millis) indicates for how long after we
     * receive a Zookeeper "disconnect" event we should wait for the connection
     * to be re-established. If the connection is not restored during this
     * interval midolman will be shutdown. Note that midolman will also be
     * shutdown if the session expires.
     */
    @ConfigInt(key = "session_gracetime", defaultValue = DEFAULT_GRACETIME_MS)
    public int getZkGraceTime();

    /**
     * ZooKeeper root directory path.
     */
    @ConfigString(key = "midolman_root_key", defaultValue = "/midonet")
    String getZkRootPath();

    /**
     * Whether to start the Curator client.
     */
    @ConfigBool(key = "curator_enabled", defaultValue = false)
    Boolean getCuratorEnabled();
}
