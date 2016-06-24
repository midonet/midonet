/*
 * Copyright 2016 Midokura SARL
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

import com.codahale.metrics.MetricRegistry._
import com.codahale.metrics.{Gauge, MetricRegistry}

import org.midonet.midolman.monitoring.metrics.VirtualTopologyMetrics.DeviceClassMetrics
import org.midonet.midolman.simulation._
import org.midonet.midolman.topology.devices.{Host, PoolHealthMonitorMap, TunnelZone}

object VirtualTopologyMetrics {

    class DeviceClassMetrics(registry: MetricRegistry, clazz: Class[_]) {

        val updateCounter =
            registry.counter(name(classOf[VirtualTopologyCounter],
                                  clazz.getSimpleName, "deviceUpdate"))
        val errorCounter =
            registry.counter(name(classOf[VirtualTopologyCounter],
                                  clazz.getSimpleName, "deviceError"))
        val completeCounter =
            registry.counter(name(classOf[VirtualTopologyCounter],
                                  clazz.getSimpleName, "deviceComplete"))
        val updateMeter =
            registry.meter(name(classOf[VirtualTopologyMeter],
                                clazz.getSimpleName, "deviceUpdate"))
        val errorMeter =
            registry.meter(name(classOf[VirtualTopologyMeter],
                                clazz.getSimpleName, "deviceError"))
        val completeMeter =
            registry.meter(name(classOf[VirtualTopologyMeter],
                                clazz.getSimpleName, "deviceComplete"))

        val latencyHistogram =
            registry.histogram(name(classOf[VirtualTopologyHistogram],
                                    clazz.getSimpleName, "deviceLatency"))
        val lifetimeHistogram =
            registry.histogram(name(classOf[VirtualTopologyHistogram],
                                    clazz.getSimpleName, "deviceLifetime"))

    }

}

class VirtualTopologyMetrics(registry: MetricRegistry,
                             devices: => Int,
                             observables: => Int,
                             cacheHits: => Long,
                             cacheMisses: => Long) {

    private val classes = Set[Class[_]](
        classOf[Bridge], classOf[Chain], classOf[Host], classOf[IPAddrGroup],
        classOf[LoadBalancer], classOf[Mirror], classOf[Pool],
        classOf[PoolHealthMonitorMap], classOf[Port], classOf[PortGroup],
        classOf[Router], classOf[RuleLogger], classOf[TunnelZone])

    val devicesGauge =
        registry.register(name(classOf[VirtualTopologyGauge], "devices"),
                          gauge(devices))
    val observablesGauge =
        registry.register(name(classOf[VirtualTopologyGauge], "observables"),
                          gauge(observables))
    val cacheHitGauge =
        registry.register(name(classOf[VirtualTopologyGauge], "cacheHit"),
                          gauge(cacheHits))
    val cacheMissGauge =
        registry.register(name(classOf[VirtualTopologyGauge], "cacheMiss"),
                          gauge(cacheMisses))

    val deviceUpdateCounter =
        registry.counter(name(classOf[VirtualTopologyCounter], "deviceUpdate"))
    val deviceErrorCounter =
        registry.counter(name(classOf[VirtualTopologyCounter], "deviceError"))
    val deviceCompleteCounter =
        registry.counter(name(classOf[VirtualTopologyCounter], "deviceComplete"))
    val deviceUpdateMeter =
        registry.meter(name(classOf[VirtualTopologyMeter], "deviceUpdate"))
    val deviceErrorMeter =
        registry.meter(name(classOf[VirtualTopologyMeter], "deviceError"))
    val deviceCompleteMeter =
        registry.meter(name(classOf[VirtualTopologyMeter], "deviceComplete"))

    val deviceLatencyHistogram =
        registry.histogram(name(classOf[VirtualTopologyHistogram], "deviceLatency"))
    val deviceLifetimeHistogram =
        registry.histogram(name(classOf[VirtualTopologyHistogram], "deviceLifetime"))

    val deviceClasses: Map[Class[_], DeviceClassMetrics] =
        classes.map { case c => c -> new DeviceClassMetrics(registry, c) }.toMap

    def deviceUpdate(clazz: Class[_]): Unit = {
        deviceUpdateCounter.inc()
        deviceUpdateMeter.mark()
        deviceClasses.get(clazz) match {
            case Some(metrics) =>
                metrics.updateCounter.inc()
                metrics.updateMeter.mark()
            case None =>
        }
    }

    def deviceError(clazz: Class[_]): Unit = {
        deviceErrorCounter.inc()
        deviceErrorMeter.mark()
        deviceClasses.get(clazz) match {
            case Some(metrics) =>
                metrics.errorCounter.inc()
                metrics.errorMeter.mark()
            case None =>
        }
    }

    def deviceComplete(clazz: Class[_]): Unit = {
        deviceCompleteCounter.inc()
        deviceCompleteMeter.mark()
        deviceClasses.get(clazz) match {
            case Some(metrics) =>
                metrics.completeCounter.inc()
                metrics.completeMeter.mark()
            case None =>
        }
    }

    def deviceLatency(clazz: Class[_], latency: Long): Unit = {
        deviceLatencyHistogram.update(latency)
        deviceClasses.get(clazz) match {
            case Some(metrics) =>
                metrics.latencyHistogram.update(latency)
            case None =>
        }
    }

    def deviceLifetime(clazz: Class[_], lifetime: Long): Unit = {
        deviceLifetimeHistogram.update(lifetime)
        deviceClasses.get(clazz) match {
            case Some(metrics) =>
                metrics.lifetimeHistogram.update(lifetime)
            case None =>
        }
    }

    private def gauge(f: => Long): Gauge[Long] = {
        new Gauge[Long] { override def getValue = f }
    }

}
