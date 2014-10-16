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