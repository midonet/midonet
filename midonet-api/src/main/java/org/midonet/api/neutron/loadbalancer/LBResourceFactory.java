/*
 * Copyright 2012-2013 Midokura PTE LTD.
 */
package org.midonet.api.neutron.loadbalancer;


/**
 * Resource factory used by Guice to inject LB resource classes.
 */
public interface LBResourceFactory {

    HealthMonitorResource getHealthMonitorResource();

    MemberResource getMemberResource();

    PoolResource getPoolResource();

    VipResource getVipResource();

    PoolHealthMonitorResource getPoolHealthMonitorResource();
}
