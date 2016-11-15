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

package org.midonet.cluster.services.rest_api.neutron.plugin

import java.util
import java.util.UUID

import org.midonet.cluster.rest_api.neutron.models._
import org.midonet.cluster.rest_api.{ConflictHttpException, NotFoundHttpException}

trait LoadBalancerV2Api {

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getLoadBalancerV2(id: UUID): LoadBalancerV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getLoadBalancersV2: util.List[LoadBalancerV2]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createLoadBalancerV2(lbv2: LoadBalancerV2): LoadBalancerV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateLoadBalancerV2(id: UUID, lbv2: LoadBalancerV2): LoadBalancerV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteLoadBalancerV2(id: UUID)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getPoolV2(id: UUID): PoolV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getPoolsV2: util.List[PoolV2]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createPoolV2(pool: PoolV2): PoolV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updatePoolV2(id: UUID, pool: PoolV2): PoolV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deletePoolV2(id: UUID)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getPoolMemberV2(id: UUID): PoolMemberV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getPoolMembersV2: util.List[PoolMemberV2]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createPoolMemberV2(member: PoolMemberV2): PoolMemberV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updatePoolMemberV2(id: UUID, member: PoolMemberV2): PoolMemberV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deletePoolMemberV2(id: UUID)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getListenerV2(id: UUID): ListenerV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getListenersV2: util.List[ListenerV2]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createListenerV2(l: ListenerV2): ListenerV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateListenerV2(id: UUID, l: ListenerV2): ListenerV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteListenerV2(id: UUID)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getHealthMonitorV2(id: UUID): HealthMonitorV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getHealthMonitorsV2: util.List[HealthMonitorV2]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createHealthMonitorV2(healthMonitor: HealthMonitorV2): HealthMonitorV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateHealthMonitorV2(id: UUID, healthMonitor: HealthMonitorV2): HealthMonitorV2

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteHealthMonitorV2(id: UUID)
}
