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
package org.midonet.cluster.client

import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager._

/**
 * A builder for a health monitor cluster data object.
 */
trait HealthMonitorBuilder extends Builder[HealthMonitorBuilder]{

    /**
     * Sets the ZK configuration for health monitor in the builder.
     *
     * @param healthMonitor Health monitor configuration from ZK
     */
    def setHealthMonitor(healthMonitor: HealthMonitorConfig)

}
