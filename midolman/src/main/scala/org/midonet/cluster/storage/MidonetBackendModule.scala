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
import com.google.inject.{Inject, PrivateModule, Provider, Singleton}
import com.typesafe.config.Config

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.zookeeper.ClientCnxn
import org.slf4j.LoggerFactory

import org.midonet.cluster.ZookeeperLockFactory
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}

/** This Guice module is dedicated to declare general-purpose dependencies that
  * are exposed to MidoNet components that need to access the various storage
  * backends that exist within a deployment.  It should not include any
  * dependencies linked to any specific service or component. */
class MidonetBackendModule(val conf: MidonetBackendConfig)
    extends PrivateModule {

    private val log = LoggerFactory.getLogger("org.midonet.nsdb")
    configureClientBuffer()

    def this(config: Config) = this(new MidonetBackendConfig(config))

    override def configure(): Unit = {
        bindCurator()
        bindStorage()
        bindLockFactory()

        bind(classOf[MidonetBackendConfig]).toInstance(conf)
        expose(classOf[MidonetBackendConfig])
    }

    protected def bindLockFactory(): Unit = {
        bind(classOf[ZookeeperLockFactory]).asEagerSingleton()
        expose(classOf[ZookeeperLockFactory])
    }

    protected def bindStorage(): Unit = {
        requireBinding(classOf[MetricRegistry])
        bind(classOf[MidonetBackend])
            .to(classOf[MidonetBackendService])
            .in(classOf[Singleton])
        expose(classOf[MidonetBackend])
    }

    protected def bindCurator(): Unit = {
        bind(classOf[CuratorFramework])
            .toProvider(classOf[CuratorFrameworkProvider])
            .asEagerSingleton()
        expose(classOf[CuratorFramework])
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

class CuratorFrameworkProvider @Inject()(cfg: MidonetBackendConfig)
    extends Provider[CuratorFramework] {
    override def get(): CuratorFramework = CuratorFrameworkFactory.newClient(
        cfg.hosts, new ExponentialBackoffRetry(cfg.retryMs.toInt,
                                               cfg.maxRetries))
}

