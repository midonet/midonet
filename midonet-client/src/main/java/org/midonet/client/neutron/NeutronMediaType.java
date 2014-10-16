/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.client.neutron;

public class NeutronMediaType {

    public final static String NEUTRON_JSON_V1 =
        "application/vnd.org.midonet.neutron.Neutron-v1+json";
    public final static String NEUTRON_JSON_V2 =
        "application/vnd.org.midonet.neutron.Neutron-v2+json";

    public final static String NETWORK_JSON_V1 =
        "application/vnd.org.midonet.neutron.Network-v1+json";
    public final static String NETWORKS_JSON_V1 =
        "application/vnd.org.midonet.neutron.Networks-v1+json";

    public final static String SUBNET_JSON_V1 =
        "application/vnd.org.midonet.neutron.Subnet-v1+json";
    public final static String SUBNETS_JSON_V1 =
        "application/vnd.org.midonet.neutron.Subnets-v1+json";

    public final static String PORT_JSON_V1 =
        "application/vnd.org.midonet.neutron.Port-v1+json";
    public final static String PORTS_JSON_V1 =
        "application/vnd.org.midonet.neutron.Ports-v1+json";

    public final static String ROUTER_JSON_V1 =
        "application/vnd.org.midonet.neutron.Router-v1+json";
    public final static String ROUTERS_JSON_V1 =
        "application/vnd.org.midonet.neutron.Routers-v1+json";

    public final static String ROUTER_INTERFACE_V1 =
        "application/vnd.org.midonet.neutron.RouterInterface-v1+json";

    public final static String FLOATING_IP_JSON_V1 =
        "application/vnd.org.midonet.neutron.FloatingIp-v1+json";
    public final static String FLOATING_IPS_JSON_V1 =
        "application/vnd.org.midonet.neutron.FloatingIps-v1+json";

    public final static String SECURITY_GROUP_JSON_V1 =
        "application/vnd.org.midonet.neutron.SecurityGroup-v1+json";
    public final static String SECURITY_GROUPS_JSON_V1 =
        "application/vnd.org.midonet.neutron.SecurityGroups-v1+json";
    public final static String SECURITY_GROUP_RULE_JSON_V1 =
        "application/vnd.org.midonet.neutron.SecurityGroupRule-v1+json";
    public final static String SECURITY_GROUP_RULES_JSON_V1 =
        "application/vnd.org.midonet.neutron.SecurityGroupRules-v1+json";
}
