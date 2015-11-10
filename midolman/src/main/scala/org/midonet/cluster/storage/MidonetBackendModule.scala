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

package org.midonet.cluster.storage

import java.lang.reflect.{Field, Modifier}

import scala.util.control.NonFatal

import com.codahale.metrics.MetricRegistry
import com.google.inject.AbstractModule
import com.typesafe.config.Config
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.ClientCnxn
import org.slf4j.LoggerFactory

import org.midonet.cluster._
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}

/** This Guice module is dedicated to declare general-purpose dependencies that
  * are exposed to MidoNet components that need to access the various storage
  * backends that exist within a deployment.  It should not include any
  * dependencies linked to any specific service or component. */
class MidonetBackendModule(val conf: MidonetBackendConfig,
                           metricRegistry: MetricRegistry = new MetricRegistry)
    extends AbstractModule {

    private val log = LoggerFactory.getLogger("org.midonet.nsdb")
    configureClientBuffer()

    def this(config: Config, metricRegistry: MetricRegistry) =
        this(new MidonetBackendConfig(config), metricRegistry)

    override def configure(): Unit = {
        val curator =  bindCuratorFramework()
        val storage = backend(curator)
        bind(classOf[MidonetBackend]).toInstance(storage)
        bindLockFactory()
        bind(classOf[MidonetBackendConfig]).toInstance(conf)
    }

    protected def bindLockFactory(): Unit = {
        bind(classOf[ZookeeperLockFactory]).asEagerSingleton()
    }

    protected def backend(curatorFramework: CuratorFramework): MidonetBackend =
        new MidonetBackendService(conf, curatorFramework, metricRegistry)

    protected def bindCuratorFramework() = {
        val curator = CuratorFrameworkFactory.newClient(
            conf.hosts,
            new ExponentialBackoffRetry(
                conf.retryMs.toInt, conf.maxRetries))
        bind(classOf[CuratorFramework]).toInstance(curator)
        curator
    }

    private def configureClientBuffer(): Unit = {
        // Try configure the buffer size using the system property.
        System.setProperty("jute.maxbuffer", Integer.toString(conf.bufferSize))
        if (ClientCnxn.packetLen != conf.bufferSize) {
            // If the static variable as already initialized, fallback to
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
                    log.warn("Unable to set the buffer size to:" +
                             s"${conf.bufferSize} using: ${ClientCnxn.packetLen}")
            }
        }
    }
}
