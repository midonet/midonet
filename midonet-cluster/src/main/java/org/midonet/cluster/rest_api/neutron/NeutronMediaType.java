/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.rest_api.neutron;

public final class NeutronMediaType {
    public static final String NETWORK_JSON_V1 = "application/vnd.org.midonet.neutron.Network-v1+json";
    public static final String NETWORKS_JSON_V1 = "application/vnd.org.midonet.neutron.Networks-v1+json";
    public static final String SUBNET_JSON_V1 = "application/vnd.org.midonet.neutron.Subnet-v1+json";
    public static final String SUBNETS_JSON_V1 = "application/vnd.org.midonet.neutron.Subnets-v1+json";
    public static final String PORT_JSON_V1 = "application/vnd.org.midonet.neutron.Port-v1+json";
    public static final String PORTS_JSON_V1 = "application/vnd.org.midonet.neutron.Ports-v1+json";
    public static final String ROUTER_JSON_V1 = "application/vnd.org.midonet.neutron.Router-v1+json";
    public static final String ROUTERS_JSON_V1 = "application/vnd.org.midonet.neutron.Routers-v1+json";
    public static final String ROUTER_INTERFACE_V1 = "application/vnd.org.midonet.neutron.RouterInterface-v1+json";
    public static final String FLOATING_IP_JSON_V1 = "application/vnd.org.midonet.neutron.FloatingIp-v1+json";
    public static final String FLOATING_IPS_JSON_V1 = "application/vnd.org.midonet.neutron.FloatingIps-v1+json";
    public static final String SECURITY_GROUP_JSON_V1 = "application/vnd.org.midonet.neutron.SecurityGroup-v1+json";
    public static final String SECURITY_GROUPS_JSON_V1 = "application/vnd.org.midonet.neutron.SecurityGroups-v1+json";
    public static final String SECURITY_GROUP_RULE_JSON_V1 = "application/vnd.org.midonet.neutron.SecurityGroupRule-v1+json";
    public static final String SECURITY_GROUP_RULES_JSON_V1 = "application/vnd.org.midonet.neutron.SecurityGroupRules-v1+json";
    public static final String VIP_JSON_V1 = "application/vnd.org.midonet.neutron.lb.Vip-v1+json";
    public static final String VIPS_JSON_V1 = "application/vnd.org.midonet.neutron.lb.Vips-v1+json";
    public static final String POOL_JSON_V1 = "application/vnd.org.midonet.neutron.lb.Pool-v1+json";
    public static final String POOLS_JSON_V1 = "application/vnd.org.midonet.neutron.lb.Pools-v1+json";
    public static final String MEMBER_JSON_V1 = "application/vnd.org.midonet.neutron.lb.PoolMember-v1+json";
    public static final String MEMBERS_JSON_V1 = "application/vnd.org.midonet.neutron.lb.PoolMembers-v1+json";
    public static final String HEALTH_MONITOR_JSON_V1 = "application/vnd.org.midonet.neutron.lb.HealthMonitor-v1+json";
    public static final String HEALTH_MONITORS_JSON_V1 = "application/vnd.org.midonet.neutron.lb.HealthMonitors-v1+json";
    public static final String LOAD_BALANCER_JSON_V2 = "application/vnd.org.midonet.neutron.lb.LoadBalancer-v2+json";
    public static final String LOAD_BALANCERS_JSON_V2 = "application/vnd.org.midonet.neutron.lb.LoadBalancers-v2+json";
    public static final String LB_LISTENER_JSON_V2 = "application/vnd.org.midonet.neutron.lb.Listener-v2+json";
    public static final String LB_LISTENERS_JSON_V2 = "application/vnd.org.midonet.neutron.lb.Listeners-v2+json";
    public static final String POOL_JSON_V2 = "application/vnd.org.midonet.neutron.lb.Pool-v2+json";
    public static final String POOLS_JSON_V2 = "application/vnd.org.midonet.neutron.lb.Pools-v2+json";
    public static final String POOL_MEMBER_JSON_V2 = "application/vnd.org.midonet.neutron.lb.PoolMember-v2+json";
    public static final String POOL_MEMBERS_JSON_V2 = "application/vnd.org.midonet.neutron.lb.PoolMembers-v2+json";
    public static final String HEALTH_MONITOR_JSON_V2 = "application/vnd.org.midonet.neutron.lb.HealthMonitor-v2+json";
    public static final String HEALTH_MONITORS_JSON_V2 = "application/vnd.org.midonet.neutron.lb.HealthMonitors-v2+json";
    public static final String POOL_HEALTH_MONITOR_JSON_V1 = "application/vnd.org.midonet.neutron.lb.PoolHealthMonitor-v1+json";
    public static final String FIREWALL_JSON_V1 = "application/vnd.org.midonet.neutron.Firewall-v1+json";
}
