/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.client.neutron.loadbalancer;

public class LBMediaType {

    public final static String VIP_JSON_V1 =
        "application/vnd.org.midonet.neutron.lb.Vip-v1+json";
    public final static String VIPS_JSON_V1 =
        "application/vnd.org.midonet.neutron.lb.Vips-v1+json";

    public final static String POOL_JSON_V1 =
        "application/vnd.org.midonet.neutron.lb.Pool-v1+json";
    public final static String POOLS_JSON_V1 =
        "application/vnd.org.midonet.neutron.lb.Pool-v1+json";

    public final static String MEMBER_JSON_V1 =
        "application/vnd.org.midonet.neutron.lb.Member-v1+json";
    public final static String MEMBERS_JSON_V1 =
        "application/vnd.org.midonet.neutron.lb.Members-v1+json";

    public final static String HEALTH_MONITOR_JSON_V1 =
        "application/vnd.org.midonet.neutron.lb.HealthMonitor-v1+json";
    public final static String HEALTH_MONITORS_JSON_V1 =
        "application/vnd.org.midonet.neutron.lb.HealthMonitors-v1+json";
}
