/*
 * Copyright 2014 - 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman.config;

import org.midonet.cluster.config.CassandraConfig;
import org.midonet.cluster.config.ZookeeperConfig;
import org.midonet.config.ConfigBool;
import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;
import org.midonet.config.ConfigString;

/**
 * Configuration entries belonging to the midolman stanza.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 5/21/12
 */
@ConfigGroup(MidolmanConfig.GROUP_NAME)
public interface MidolmanConfig
    extends ZookeeperConfig, CassandraConfig, DatapathConfig,
            ArpTableConfig, HealthMonitorConfig, ClusterConfig
{
    public final static String GROUP_NAME = "midolman";
    public final static short DEFAULT_MTU = 1500;

    @ConfigBool(key = "enable_bgp", defaultValue = true)
    public boolean getMidolmanBGPEnabled();

    @ConfigBool(key = "enable_bridge_arp", defaultValue = false)
    public boolean getMidolmanBridgeArpEnabled();

    @ConfigInt(key = "bgp_port_start_index", defaultValue = 0)
    public int getMidolmanBGPPortStartIndex();

    @ConfigInt(key = "bgp_keepalive", defaultValue = 60)
    public int getMidolmanBGPKeepAlive();

    @ConfigInt(key = "bgp_holdtime", defaultValue = 180)
    public int getMidolmanBGPHoldtime();

    @ConfigInt(key = "bgp_connect_retry", defaultValue = 120)
    public int getMidolmanBGPConnectRetry();

    @ConfigString(key = "bgpd_config", defaultValue = "/etc/quagga")
    public String pathToBGPDConfig();

    @ConfigInt(key = "dhcp_mtu", defaultValue = DEFAULT_MTU)
    public int getDhcpMtu();

    @ConfigInt(key = "check_flow_expiration_interval", defaultValue = 10000)
    public int getFlowExpirationInterval();

    @ConfigInt(key = "idle_flow_tolerance_interval", defaultValue = 10000)
    public int getIdleFlowToleranceInterval();

    @ConfigBool(key = "enable_dashboard", defaultValue = false)
    public boolean getDashboardEnabled();

    @ConfigString(key = "jetty_xml", defaultValue = "/etc/midolman/jetty/etc/jetty.xml")
    public String pathToJettyXml();

    @ConfigGroup("bridge")
    @ConfigInt(key = "mac_port_mapping_expire_millis", defaultValue = 30000)
    public int getMacPortMappingExpireMillis();

    @ConfigGroup("router")
    @ConfigInt(key = "max_bgp_peer_routes", defaultValue = 200)
    public int getMaxBgpPeerRoutes();

    @ConfigInt(key = "simulation_threads", defaultValue = 1)
    public int getSimulationThreads();

    @ConfigInt(key = "output_channels", defaultValue = 1)
    public int getNumOutputChannels();

    @ConfigString(key = "input_channel_threading", defaultValue = "one_to_many")
    public String getInputChannelThreading();

}
