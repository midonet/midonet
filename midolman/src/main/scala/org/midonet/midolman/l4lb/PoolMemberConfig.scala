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
package org.midonet.midolman.l4lb

import java.util.UUID

/**
  * Represents a pool member object local to the host.  The host that acts as a
  * health monitor only needs to know minimal amount of pool member data to
  * run the service.
  */
class PoolMemberConfig(val admingStateUp: Boolean, val id: UUID,
                       val weight: Int, val address: String, val port: Int) {
    def isConfigurable = id != null && port > 0 && address != null

    override def equals(other: Any) = other match {
        case that: PoolMemberConfig =>
            this.weight == that.weight &&
            this.admingStateUp == that.admingStateUp &&
            this.id == that.id &&
            this.address == that.address &&
            this.port == that.port
        case _ => false
    }
}
