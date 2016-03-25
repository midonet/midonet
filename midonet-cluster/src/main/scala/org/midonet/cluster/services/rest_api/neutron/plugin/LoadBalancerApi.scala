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

trait LoadBalancerApi {

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getPool(id: UUID): Pool

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getPools: util.List[Pool]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createPool(pool: Pool)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updatePool(id: UUID, pool: Pool)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deletePool(id: UUID)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getMember(id: UUID): Member

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getMembers: util.List[Member]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getMembers(ids: util.List[UUID]): util.List[Member]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createMember(member: Member)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateMember(id: UUID, member: Member)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteMember(id: UUID)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getVip(id: UUID): VIP

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getVips: util.List[VIP]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createVip(vip: VIP)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateVip(id: UUID, vip: VIP)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteVip(id: UUID)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getHealthMonitor(id: UUID): HealthMonitor

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getHealthMonitors: util.List[HealthMonitor]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createHealthMonitor(healthMonitor: HealthMonitor)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateHealthMonitor(id: UUID, healthMonitor: HealthMonitor)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteHealthMonitor(id: UUID)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createPoolHealthMonitor(poolId: UUID,
                                poolHealthMonitor: PoolHealthMonitor)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deletePoolHealthMonitor(poolId: UUID, hmId: UUID)
}