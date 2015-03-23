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

package org.midonet.midolman.topology.devices

import java.util.UUID

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.midolman.state.l4lb.{LBStatus, HealthMonitorType}
import org.midonet.midolman.topology.{TopologyBuilder, TopologyMatchers}

@RunWith(classOf[JUnitRunner])
class HealthMonitorConversionTest extends FeatureSpec
                        with Matchers
                        with TopologyBuilder
                        with TopologyMatchers {

    feature("Conversion for Health Monitor") {
        scenario("From Protocol Buffer message") {
            val proto = createTopologyObject(
                classOf[Topology.HealthMonitor], Map(
                "id" -> UUID.randomUUID(),
                "admin_state_up" -> true,
                "type" -> Topology.HealthMonitor.HealthMonitorType.TCP,
                "status" -> Commons.LBStatus.ACTIVE,
                "delay" -> 5,
                "timeout" -> 7,
                "max_retries" -> 13
            ))
            val zoomObj = ZoomConvert.fromProto(proto, classOf[HealthMonitor])
            zoomObj shouldBeDeviceOf proto
        }

        scenario("To Protocol Buffer message") {
            val zoomObj = new HealthMonitor(
                id = UUID.randomUUID(),
                adminStateUp = true,
                t = HealthMonitorType.TCP,
                status = LBStatus.ACTIVE,
                delay = 5,
                timeout = 7,
                maxRetries = 11
            )
            val proto = ZoomConvert.toProto(zoomObj, classOf[Topology.HealthMonitor])
            zoomObj shouldBeDeviceOf proto
        }
    }
}
