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


/**
  * Represents a health monitor object local to the host.  The host that acts
  * as a health monitor only needs to know minimal amount of data to run the
  * service.
  */
class HealthMonitorConfig(val adminStateUp: Boolean, val delay: Int,
                          val timeout: Int, val maxRetries: Int) {
    def isConfigurable = adminStateUp && delay > 0 && timeout > 0 && maxRetries > 0

    override def equals(other: Any) = other match {
        case that: HealthMonitorConfig =>
            this.adminStateUp == that.adminStateUp &&
            this.delay == that.delay &&
            this.timeout == that.timeout &&
            this.maxRetries == that.maxRetries
        case _ => false
    }
}
