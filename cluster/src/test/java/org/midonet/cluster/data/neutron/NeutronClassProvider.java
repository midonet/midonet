/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;

import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.Pool;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;

import static junitparams.JUnitParamsRunner.$;

public class NeutronClassProvider {

    public static Object[] neutronClasses() {

        return $(
                $(ExternalGatewayInfo.class),
                $(FloatingIp.class),
                $(IPAllocation.class),
                $(IPAllocationPool.class),
                $(Network.class),
                $(Port.class),
                $(Route.class),
                $(Router.class),
                $(RouterInterface.class),
                $(SecurityGroup.class),
                $(SecurityGroupRule.class),
                $(Subnet.class),
                // LBaaS DTOs
                $(Pool.class),
                $(HealthMonitor.class),
                $(Member.class),
                $(VIP.class)
        );
    }

}