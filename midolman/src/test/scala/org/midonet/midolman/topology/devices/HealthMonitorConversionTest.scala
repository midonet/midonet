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

import java.util.{Random, UUID}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.{Commons, Topology => Proto}
import org.midonet.cluster.topology.{TopologyBuilder, TopologyMatchers}
import org.midonet.midolman.state.l4lb.{HealthMonitorType, LBStatus}

@RunWith(classOf[JUnitRunner])
class HealthMonitorConversionTest extends FeatureSpec
                        with Matchers
                        with TopologyBuilder
                        with TopologyMatchers {

    val random = new Random()

    feature("Conversion for Health Monitor") {
        scenario("From Protocol Buffer message") {
            val proto = createHealthMonitor(
                adminStateUp = true,
                healthMonitorType = Some(Proto.HealthMonitor.HealthMonitorType.TCP),
                status = Some(Commons.LBStatus.ACTIVE),
                delay = Some(random.nextInt()),
                timeout = Some(random.nextInt()),
                maxRetries = Some(random.nextInt())
            )
            val zoomObj = ZoomConvert.fromProto(proto, classOf[HealthMonitor])
            zoomObj shouldBeDeviceOf proto
        }

        scenario("From Protocol Buffer message with default values") {
            val proto = createHealthMonitor()
            val zoomObj = ZoomConvert.fromProto(proto, classOf[HealthMonitor])
            zoomObj shouldBeDeviceOf proto
        }

        scenario("To Protocol Buffer message") {
            val zoomObj = new HealthMonitor(
                id = UUID.randomUUID(),
                adminStateUp = true,
                t = HealthMonitorType.TCP,
                status = LBStatus.ACTIVE,
                delay = random.nextInt(),
                timeout = random.nextInt(),
                maxRetries = random.nextInt()
            )
            val proto = ZoomConvert.toProto(zoomObj, classOf[Proto.HealthMonitor])
            zoomObj shouldBeDeviceOf proto
        }
    }
}
