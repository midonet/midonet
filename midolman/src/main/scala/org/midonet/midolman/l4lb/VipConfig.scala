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
import org.midonet.midolman.state.l4lb.VipSessionPersistence

/**
  * Represents a pool member object local to the host.  The host that acts as a
  * health monitor only needs to know minimal amount of pool member data to
  * run the service.
  */
class VipConfig(val adminStateUp: Boolean, val id: UUID, val ip: String,
                val port: Int, val sessionPersistence: VipSessionPersistence) {
    def isConfigurable = adminStateUp && id != null && ip != null && port > 0

    override def equals(other: Any) = other match {
        case that: VipConfig =>
            this.adminStateUp == that.adminStateUp &&
            this.id == that.id &&
            this.ip == that.ip &&
            this.port == that.port &&
            this.sessionPersistence == that.sessionPersistence
        case _ => false
    }
}
