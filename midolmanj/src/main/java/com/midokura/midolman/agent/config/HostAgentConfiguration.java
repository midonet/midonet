/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.config;

/**
 * Interface that provides access to various configuration values
 * available to the agent.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
public interface HostAgentConfiguration {

    /**
     * Returns the time to wait between local information scans (in millis).
     *
     * @return the time we want to wait between subsequent scans.
     */
    public int getWaitTimeBetweenScans();

    /**
     * OpenvSwitch related configuration: the switch ip address.
     *
     * @return the switch ip address as a string
     */
    public String getOpenvSwitchIpAddr();

    /**
     * OpenvSwitch related configuration: the switch tcp port.
     *
     * @return the switch port number
     */
    public int getOpenvSwitchTcpPort();

    /**
     * ZooKeeper related configuration: the list of hosts in a zookeeper cluster.
     *
     * @return the list as a comma separated string.
     */
    public String getZooKeeperHosts();

    /**
     * ZooKeeper related configuration: the timeout session.
     *
     * @return the timeout value of the zookeeper connection.
     */
    public int getZooKeeperSessionTimeout();

    /**
     * ZooKeeper related configuration: the midolman configuration root node.
     *
     * @return the root node path as a string
     */
    public String getZooKeeperBasePath();

    /**
     * Get the unique Id stored in the config file
     * @return the unique Id
     */
    public String getId();

    /**
     * Get the path of the properties file
     * @return properties file
     */
    public String getPropertiesFilePath();

    /**
     * Get the amount of time to wait during the generate host ID loop
     * @return the wait time
     */
    public int getWaitTimeForUniqueHostId();

    /**
     * // TODO
     */
    public String getMidolmanExternalIdKey();

    /**
     * // TODO:
     * @return
     */
    public String getVrnRouterNetworkId();
}
