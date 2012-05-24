/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.config;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.HierarchicalINIConfiguration;
import org.apache.commons.configuration.SubnodeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Basic implementation of a {@link HostAgentConfiguration} that can be
 * constructed either via reading from a config file or by directly providing
 * a {@link HierarchicalConfiguration} object.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/8/12
 */
@Singleton
public class IniBasedHostAgentConfiguration
    implements HostAgentConfiguration {

    private final static Logger log =
        LoggerFactory.getLogger(IniBasedHostAgentConfiguration.class);

    HierarchicalConfiguration config;

    public static final String AGENT_CONFIG_LOCATION = "AGENT_CONFIG_LOCATION";

    @Inject
    public IniBasedHostAgentConfiguration(@Named(AGENT_CONFIG_LOCATION)
                                          String configFileLocation)
        throws ConfigurationException {
        this(new HierarchicalINIConfiguration(configFileLocation));
    }

    protected IniBasedHostAgentConfiguration(HierarchicalConfiguration config) {
        this.config = config;
    }

    @Override
    public int getWaitTimeBetweenScans() {
        return safelyGetInt("midolman-agent", "wait_time_between_scans", 500);
    }

    @Override
    public int getOpenvSwitchTcpPort() {
        return safelyGetInt("openvswitch", "openvswitchdb_tcp_port", 6634);
    }

    @Override
    public String getOpenvSwitchIpAddr() {
        return safelyGetString("openvswitch", "openvswitchdb_ip_addr",
                               "127.0.0.1");
    }

    @Override
    public String getZooKeeperHosts() {
        return safelyGetString("zookeeper", "zookeeper_hosts",
                               "127.0.0.1:2181");
    }

    @Override
    public int getZooKeeperSessionTimeout() {
        return safelyGetInt("zookeeper", "session_timeout", 30000);
    }

    @Override
    public String getZooKeeperBasePath() {
        return safelyGetString("midolman-agent", "midolman_root_key",
                               "/midonet/v1/midolman");
    }

    @Override
    public String getId() {
        return safelyGetString("midolman-agent", "host_uuid", "");
    }

    @Override
    public String getPropertiesFilePath() {
        return safelyGetString("midolman-agent", "properties_file",
                               "host_uuid.properties");
    }

    @Override
    public int getWaitTimeForUniqueHostId() {
        return safelyGetInt("midolman-agent", "wait_time_gen_id", 1000);
    }

    @Override
    public String getMidolmanExternalIdKey() {
        return safelyGetString("openvswitch", "midolman_ext_id_key",
                               "midolman-vnet");
    }

    @Override
    public String getVrnRouterNetworkId() {
        return safelyGetString("vrn", "router_network_id",
                               "01234567-0123-0123-aaaa-0123456789ab");
    }

    protected int safelyGetInt(String group, String key, int defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
            return subConfig.getInt(key, defaultValue);
        } catch (IllegalArgumentException ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }

    protected String safelyGetString(String group, String key,
                                     String defaultValue) {
        try {
            SubnodeConfiguration subConfig = config.configurationAt(group);
            return subConfig.getString(key, defaultValue);
        } catch (IllegalArgumentException ex) {
            // fail properly if the configuration is missing this group
            return defaultValue;
        }
    }
}
