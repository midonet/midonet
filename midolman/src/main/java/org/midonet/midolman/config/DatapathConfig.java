/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.midolman.config;

import org.midonet.config.ConfigGroup;
import org.midonet.config.ConfigInt;

@ConfigGroup(DatapathConfig.GROUP_NAME)
public interface DatapathConfig {

    public final static String GROUP_NAME = "datapath";

    @ConfigInt(key = "send_buffer_pool_initial_size", defaultValue = 128)
    public int getSendBufferPoolInitialSize();
    @ConfigInt(key = "send_buffer_pool_max_size", defaultValue = 512)
    public int getSendBufferPoolMaxSize();
    @ConfigInt(key = "send_buffer_pool_buf_size_kb", defaultValue = 16)
    public int getSendBufferPoolBufSizeKb();

    @ConfigInt(key = "max_flow_count", defaultValue = 10000)
    public int getDatapathMaxFlowCount();

    @ConfigInt(key = "msgs_per_batch", defaultValue = 200)
    public int getMaxMessagesPerBatch();

    /**
     * The wildcard flows have idle times, so the table should take care of itself. Having a smaller table (a limited
     * size table) means that the system would be potentially evicting valid flows often, causing more simulations and
     * hence, more CPU usage.
     */
    @ConfigInt(key = "max_wildcard_flow_count", defaultValue = 10000)
    public int getDatapathMaxWildcardFlowCount();

    @ConfigInt(key = "vxlan_udp_port", defaultValue = -1)
    public int getVxLanUdpPort();
}
