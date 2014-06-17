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

    @ConfigInt(key = "vxlan_vtep_udp_port", defaultValue = 4789)
    public int getVxLanVtepUdpPort();

    @ConfigInt(key = "vxlan_overlay_udp_port", defaultValue = 6677)
    public int getVxLanOverlayUdpPort();

    @ConfigInt(key = "global_incoming_burst_capacity", defaultValue = 20000)
    public int getGlobalIncomingBurstCapacity();

    @ConfigInt(key = "vm_incoming_burst_capacity", defaultValue = 8000)
    public int getVmIncomingBurstCapacity();

    @ConfigInt(key = "tunnel_incoming_burst_capacity", defaultValue = 10000)
    public int getTunnelIncomingBurstCapacity();

    @ConfigInt(key = "vtep_incoming_burst_capacity", defaultValue = 2000)
    public int getVtepIncomingBurstCapacity();
}
