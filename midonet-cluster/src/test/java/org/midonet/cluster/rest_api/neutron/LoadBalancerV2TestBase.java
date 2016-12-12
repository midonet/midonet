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

import java.util.UUID;

import org.midonet.cluster.rest_api.ResourceTest;
import org.midonet.cluster.rest_api.neutron.models.HealthMonitorV2;
import org.midonet.cluster.rest_api.neutron.models.ListenerV2;
import org.midonet.cluster.rest_api.neutron.models.LoadBalancerV2;
import org.midonet.cluster.rest_api.neutron.models.PoolMemberV2;
import org.midonet.cluster.rest_api.neutron.models.PoolV2;

abstract class LoadBalancerV2TestBase extends ResourceTest {

    public static LoadBalancerV2 lb() {
        return lb(UUID.randomUUID());
    }

    public static LoadBalancerV2 lb(UUID id) {
        LoadBalancerV2 lb = new LoadBalancerV2();
        lb.id = id;
        return lb;
    }

    public static PoolV2 pool() {
        return pool(UUID.randomUUID());
    }

    public static PoolV2 pool(UUID id) {
        PoolV2 p = new PoolV2();
        p.id = id;
        return p;
    }

    public static ListenerV2 listener(LoadBalancerV2 lb) {
        return listener(lb, UUID.randomUUID());
    }

    public static ListenerV2 listener(LoadBalancerV2 lb, UUID id) {
        ListenerV2 l = new ListenerV2();
        l.loadBalancers.add(lb);
        l.id = id;
        lb.listeners.add(id);
        return l;
    }

    public static PoolMemberV2 member(PoolV2 pool) {
        return member(pool, UUID.randomUUID());
    }

    public static PoolMemberV2 member(PoolV2 pool, UUID id) {
        PoolMemberV2 m = new PoolMemberV2();
        m.poolId = pool.id;
        m.id = id;
        pool.addMember(m.id);
        return m;
    }

    public static HealthMonitorV2 hm() {
        return hm(UUID.randomUUID());
    }

    public static HealthMonitorV2 hm(UUID id) {
        HealthMonitorV2 hm= new HealthMonitorV2();
        hm.id = id;
        return hm;
    }
}
