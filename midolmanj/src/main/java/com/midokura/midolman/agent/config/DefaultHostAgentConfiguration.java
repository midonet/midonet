/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.config.AbstractIniBasedConfiguration;

/**
 * Basic implementation of a {@link HostAgentConfiguration} that can be
 * constructed either via reading from a config file or by directly providing
 * a {@link HierarchicalConfiguration} object.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
@Singleton
public class DefaultHostAgentConfiguration
    extends AbstractIniBasedConfiguration
    implements HostAgentConfiguration {

    private final static Logger log =
        LoggerFactory.getLogger(DefaultHostAgentConfiguration.class);

    public static final String AGENT_CONFIG_LOCATION = "AGENT_CONFIG_LOCATION";

    @Inject
    public DefaultHostAgentConfiguration(@Named(AGENT_CONFIG_LOCATION)
                                         String configFileLocation)
        throws ConfigurationException {
        super(configFileLocation);
    }

    protected DefaultHostAgentConfiguration(HierarchicalConfiguration config) {
        super(config);
    }

    @Override
    public int getWaitTimeBetweenScans() {
        return safeGetInt("midolman-agent", "wait_time_between_scans", 500);
    }

    @Override
    public int getOpenvSwitchTcpPort() {
        return safeGetInt("openvswitch", "openvswitchdb_tcp_port", 6634);
    }

    @Override
    public String getOpenvSwitchIpAddr() {
        return safeGetString("openvswitch", "openvswitchdb_ip_addr",
                             "127.0.0.1");
    }

    @Override
    public String getZooKeeperHosts() {
        return safeGetString("zookeeper", "zookeeper_hosts",
                             "127.0.0.1:2181");
    }

    @Override
    public int getZooKeeperSessionTimeout() {
        return safeGetInt("zookeeper", "session_timeout", 30000);
    }

    @Override
    public String getZooKeeperBasePath() {
        return safeGetString("midolman-agent", "midolman_root_key",
                             "/midonet/v1/midolman");
    }

    @Override
    public String getId() {
        return safeGetString("midolman-agent", "host_uuid", "");
    }

    @Override
    public String getPropertiesFilePath() {
        return safeGetString("midolman-agent", "properties_file",
                             "host_uuid.properties");
    }

    @Override
    public int getWaitTimeForUniqueHostId() {
        return safeGetInt("midolman-agent", "wait_time_gen_id", 1000);
    }

    @Override
    public String getMidolmanExternalIdKey() {
        return safeGetString("openvswitch", "midolman_ext_id_key",
                             "midolman-vnet");
    }

    @Override
    public String getVrnRouterNetworkId() {
        return safeGetString("vrn", "router_network_id",
                             "01234567-0123-0123-aaaa-0123456789ab");
    }
}
