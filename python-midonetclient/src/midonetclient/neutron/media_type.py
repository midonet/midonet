# Copyright (c) 2015 Midokura SARL, All Rights Reserved.
# All Rights Reserved
#
# Licensed under the Apache License, Version 2.0 (the "License"); you may
# not use this file except in compliance with the License. You may obtain
# a copy of the License at
#
# http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
# WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
# License for the specific language governing permissions and limitations
# under the License.


NEUTRON = "application/vnd.org.midonet.neutron.Neutron-v3+json"
NETWORK = "application/vnd.org.midonet.neutron.Network-v1+json"
NETWORKS = "application/vnd.org.midonet.neutron.Networks-v1+json"
SUBNET = "application/vnd.org.midonet.neutron.Subnet-v1+json"
SUBNETS = "application/vnd.org.midonet.neutron.Subnets-v1+json"
PORT = "application/vnd.org.midonet.neutron.Port-v1+json"
PORTS = "application/vnd.org.midonet.neutron.Ports-v1+json"

# L3 Extension
ROUTER = "application/vnd.org.midonet.neutron.Router-v1+json"
ROUTERS = "application/vnd.org.midonet.neutron.Routers-v1+json"
ROUTER_INTERFACE = \
    "application/vnd.org.midonet.neutron.RouterInterface-v1+json"
FLOATING_IP = "application/vnd.org.midonet.neutron.FloatingIp-v1+json"
FLOATING_IPS = "application/vnd.org.midonet.neutron.FloatingIps-v1+json"

# Security Groups extension
SECURITY_GROUP = "application/vnd.org.midonet.neutron.SecurityGroup-v1+json"
SECURITY_GROUPS = "application/vnd.org.midonet.neutron.SecurityGroups-v1+json"
SG_RULE = "application/vnd.org.midonet.neutron.SecurityGroupRule-v1+json"
SG_RULES = "application/vnd.org.midonet.neutron.SecurityGroupRules-v1+json"

# Load Balancer extension
VIP = "application/vnd.org.midonet.neutron.lb.Vip-v1+json"
VIPS = "application/vnd.org.midonet.neutron.lb.Vips-v1+json"

POOL = "application/vnd.org.midonet.neutron.lb.Pool-v1+json"
POOLS = "application/vnd.org.midonet.neutron.lb.Pools-v1+json"

MEMBER = "application/vnd.org.midonet.neutron.lb.PoolMember-v1+json"
MEMBERS = "application/vnd.org.midonet.neutron.lb.PoolMembers-v1+json"

HEALTH_MONITOR = "application/vnd.org.midonet.neutron.lb.HealthMonitor-v1+json"
HEALTH_MONITORS = ("application/vnd.org.midonet"
                   ".neutron.lb.HealthMonitors-v1+json")

POOL_HEALTH_MONITOR = ("application/vnd.org.midonet"
                       ".neutron.lb.PoolHealthMonitor-v1+json")

# Firewall extension
FIREWALLS = "application/vnd.org.midonet.neutron.Firewall-v1+json"
