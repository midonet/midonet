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
package org.midonet.cluster.data.neutron;

import java.util.List;
import java.util.UUID;

import org.midonet.cluster.data.neutron.loadbalancer.HealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.Member;
import org.midonet.cluster.data.neutron.loadbalancer.Pool;
import org.midonet.cluster.data.neutron.loadbalancer.PoolHealthMonitor;
import org.midonet.cluster.data.neutron.loadbalancer.VIP;
import org.midonet.cluster.rest_api.ConflictHttpException;
import org.midonet.cluster.rest_api.NotFoundHttpException;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

public interface LoadBalancerApi {

    // Pools
    Pool getPool(UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    List<Pool> getPools() throws ConflictHttpException, NotFoundHttpException;

    void createPool(Pool pool)
        throws ConflictHttpException, NotFoundHttpException;

    void updatePool(UUID id, Pool pool)
        throws ConflictHttpException, NotFoundHttpException;

    void deletePool(UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    // Members
    Member getMember(UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    List<Member> getMembers()
        throws ConflictHttpException, NotFoundHttpException;

    void createMember(Member member)
        throws ConflictHttpException, NotFoundHttpException;

    void updateMember(UUID id, Member member)
        throws ConflictHttpException, NotFoundHttpException;

    void deleteMember(UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    // Vips
    VIP getVip(UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    List<VIP> getVips() throws ConflictHttpException, NotFoundHttpException;

    void createVip(VIP vip)
        throws ConflictHttpException, NotFoundHttpException;

    void updateVip(UUID id, VIP vip)
        throws ConflictHttpException, NotFoundHttpException;

    void deleteVip(UUID id) throws ConflictHttpException, NotFoundHttpException;

    // Health Monitors
    HealthMonitor getHealthMonitor(UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    List<HealthMonitor> getHealthMonitors()
        throws ConflictHttpException, NotFoundHttpException;

    void createHealthMonitor(HealthMonitor healthMonitor)
        throws ConflictHttpException, NotFoundHttpException;

    void updateHealthMonitor(UUID id, HealthMonitor healthMonitor)
        throws ConflictHttpException, NotFoundHttpException;

    void deleteHealthMonitor(UUID id)
        throws ConflictHttpException, NotFoundHttpException;

    // Pool Health Monitors
    void createPoolHealthMonitor(UUID poolId,
                                 PoolHealthMonitor poolHealthMonitor)
        throws ConflictHttpException, NotFoundHttpException;

    void deletePoolHealthMonitor(UUID poolId, UUID hmId)
        throws ConflictHttpException, NotFoundHttpException;
}
