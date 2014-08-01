/*
 * Copyright 2012-2013 Midokura PTE LTD.
 */
package org.midonet.api.neutron;

import org.midonet.api.neutron.loadbalancer.LBResource;

/**
 * Resource factory used by Guice to inject Neutron resource classes.
 */
public interface NeutronResourceFactory {

    NetworkResource getNeutronNetworkResource();

    SubnetResource getNeutronSubnetResource();

    PortResource getNeutronPortResource();

    RouterResource getNeutronRouterResource();

    FloatingIpResource getNeutronFloatingIpResource();

    SecurityGroupResource getNeutronSecurityGroupResource();

    SecurityGroupRuleResource getNeutronSecurityGroupRuleResource();

    LBResource getLoadBalancerResource();
}
