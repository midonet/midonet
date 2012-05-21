/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.config;

import com.midokura.config.ConfigBool;
import com.midokura.config.ConfigGroup;
import com.midokura.config.ConfigInt;
import com.midokura.config.ConfigString;

/**
 * Configuration entries belonging to the OpenFlow stanza.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 6/14/12
 */
@ConfigGroup(OpenFlowConfig.GROUP_NAME)
public interface OpenFlowConfig {

    public final String GROUP_NAME = "openflow";

    @ConfigInt(key = "controller_port", defaultValue = 6633)
    int getOpenFlowControllerPort();

    @ConfigString(key = "public_ip_address", defaultValue = "127.0.0.1")
    public String getOpenFlowPublicIpAddress();

    @ConfigBool(key = "use_nxm", defaultValue = false)
    public boolean getOpenFlowUseNxm();
}
