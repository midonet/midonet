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

package org.midonet.cluster.storage

import java.lang.reflect.{Field, Modifier}
import java.util.UUID
import java.util.concurrent.Executors

import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.google.inject.AbstractModule
import com.typesafe.config.Config

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.ClientCnxn
import org.reflections.Reflections
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.StateTableStorage
import org.midonet.cluster.data.storage.model.{ArpEntry, Fip64Entry}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.{Network, Router}
import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetDiscoveryImpl}
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.NamedThreadFactory

/** This Guice module is dedicated to declare general-purpose dependencies that
  * are exposed to MidoNet components that need to access the various storage
  * backends that exist within a deployment.  It should not include any
  * dependencies linked to any specific service or component. */
class MidonetBackendModule(val conf: MidonetBackendConfig,
                           reflections: Option[Reflections],
                           metricRegistry: MetricRegistry)
    extends AbstractModule {

    private val log = LoggerFactory.getLogger("org.midonet.nsdb")
    configureClientBuffer()

    def this(config: Config, reflections: Option[Reflections],
             metricRegistry: MetricRegistry) =
        this(new MidonetBackendConfig(config), reflections, metricRegistry)

    override def configure(): Unit = {
        val curator =  bindCuratorFramework()
        val failFastCurator = failFastCuratorFramework()
        val storage = backend(curator, failFastCurator)
        bind(classOf[MidonetBackend]).toInstance(storage)
        bind(classOf[MidonetBackendConfig]).toInstance(conf)
    }

    protected def backend(curatorFramework: CuratorFramework,
                          failFastCurator: CuratorFramework): MidonetBackend = {
        new MidonetBackendService(conf, curatorFramework, failFastCurator,
                                  metricRegistry, reflections) {
            protected override def setup(storage: StateTableStorage): Unit = {
                // Setup state tables (note: we do this here because the tables
                // backed by the replicated maps are not available to the nsdb
                // module).
                storage.registerTable(classOf[Network], classOf[MAC],
                                      classOf[UUID], MidonetBackend.MacTable,
                                      classOf[MacIdStateTable])
                storage.registerTable(classOf[Network], classOf[IPv4Addr],
                                      classOf[MAC], MidonetBackend.Ip4MacTable,
                                      classOf[Ip4MacStateTable])
                storage.registerTable(classOf[Router], classOf[IPv4Addr],
                                      classOf[ArpEntry], MidonetBackend.ArpTable,
                                      classOf[ArpStateTable])
                storage.registerTable(classOf[Topology.Port], classOf[MAC],
                                      classOf[IPv4Addr], MidonetBackend.PeeringTable,
                                      classOf[MacIp4StateTable])
                storage.registerTable(classOf[Fip64Entry],
                                      classOf[AnyRef], MidonetBackend.Fip64Table,
                                      classOf[Fip64StateTable])
                storage.registerTable(classOf[UUID], classOf[AnyRef],
                                      MidonetBackend.GatewayTable,
                                      classOf[GatewayHostStateTable])
            }
        }
    }

    protected def bindCuratorFramework() = {
        val curator = CuratorFrameworkFactory.newClient(
            conf.hosts,
            new ExponentialBackoffRetry(
                conf.retryMs.toInt, conf.maxRetries))
        bind(classOf[CuratorFramework]).toInstance(curator)
        curator
    }

    protected def failFastCuratorFramework() = {
        CuratorFrameworkFactory.newClient(
            conf.hosts,
            conf.failFastSessionTimeout,
            conf.failFastSessionTimeout,
            new ExponentialBackoffRetry(
                conf.retryMs.toInt, conf.maxRetries))
    }

    private def configureClientBuffer(): Unit = {
        // Try configure the buffer size using the system property.
        System.setProperty("jute.maxbuffer", Integer.toString(conf.bufferSize))
        if (ClientCnxn.packetLen != conf.bufferSize) {
            // If the static variable is already initialized, fallback to
            // reflection.
            try {
                val field = classOf[ClientCnxn].getField("packetLen")
                val modifiers = classOf[Field].getDeclaredField("modifiers")
                modifiers.setAccessible(true)
                modifiers.setInt(field, field.getModifiers & ~Modifier.FINAL)
                field.setAccessible(true)
                field.setInt(null, conf.bufferSize)
            } catch {
                case NonFatal(e) =>
                    log.warn("Unable to set the buffer size to: " +
                             s"${conf.bufferSize}, falling back to: " +
                             s"${ClientCnxn.packetLen}")
            }
        }
    }
}
