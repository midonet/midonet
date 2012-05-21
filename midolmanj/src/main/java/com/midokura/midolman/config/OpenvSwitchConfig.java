/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.config;

import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;

/**
 * Configuration entries the belong to [openvswitch] stanza.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/14/12
 */
@ConfigGroup(OpenvSwitchConfig.GROUP_NAME)
public interface OpenvSwitchConfig {

    final static String GROUP_NAME = "openvswitch";

    /**
     * OpenvSwitch related configuration: the switch ip address.
     *
     * @return the switch ip address as a string
     */
    @ConfigString(key = "openvswitchdb_ip_addr", defaultValue = "127.0.0.1")
    String getOpenvSwitchIpAddr();

    /**
     * OpenvSwitch related configuration: the switch tcp port.
     *
     * @return the switch port number
     */
    @ConfigInt(key = "openvswitchdb_tcp_port", defaultValue = 6634)
    int getOpenvSwitchTcpPort();

    /**
     * The key name we use to associate metadata with openvswitch objects.
     */
    @ConfigString(key = "midolman_ext_id_key", defaultValue = "midolman-vnet")
    public String getOpenvSwitchMidolmanExternalIdKey();
}
