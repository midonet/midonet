/*
 * Copyright 2015 Letv Cloud Computing
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
package org.midonet.midolman.monitoring.metrics

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistry.name
import com.google.common.annotations.VisibleForTesting

import org.midonet.midolman.topology.DeviceMapper
import org.midonet.midolman.topology.VirtualTopology.Device

trait DeviceMapperMetrics {
    def deviceUpdated(): Unit
    def deviceErrorTriggered(): Unit
}

object BlackHoleDeviceMapperMetrics extends DeviceMapperMetrics {
    override def deviceUpdated(): Unit = {}
    override def deviceErrorTriggered(): Unit = {}
}

class JmxDeviceMapperMetrics[D <: Device](dm: DeviceMapper[D], registry: MetricRegistry)
    extends DeviceMapperMetrics {

    private final val deviceName = dm.getClass.getName
    private final val deviceId = dm.id.toString

    @VisibleForTesting
    val deviceUpdatedMeter = registry.meter(name(deviceName, deviceId, "deviceUpdated"))

    @VisibleForTesting
    val deviceErrors = registry.counter(name(deviceName, deviceId, "deviceErrors"))

    def deviceUpdated() = deviceUpdatedMeter.mark()

    def deviceErrorTriggered() = deviceErrors.inc()
}
