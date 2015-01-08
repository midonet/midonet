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

package org.midonet.cluster.storage

import com.google.inject.AbstractModule
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.slf4j.LoggerFactory

/** This Guice module defines exclusively dependencies that are exposed to any
  * MidoNet component that needs to access the various storage backends that
  * exist within a deployment. */
class MidonetBackendModule(cfg: MidonetBackendConfig) extends AbstractModule {

    private val log = LoggerFactory.getLogger(classOf[MidonetBackendModule])

    override def configure(): Unit = {

        log.info(s"Connecting to ZooKeeper at ${cfg.zookeeperHosts}.. ")
        // The hidden, private curator client at the root path.
        val curator = CuratorFrameworkFactory.builder()
            .connectString(cfg.zookeeperHosts)
            .retryPolicy(new ExponentialBackoffRetry(cfg.zookeeperRetryMs,
                                                     cfg.zookeeperMaxRetries))
            .build()
        curator.start()
        curator.blockUntilConnected()
        log.info(".. ZooKeeper is connected, ensuring root paths ..")

        if (curator.checkExists().forPath(cfg.zookeeperRootPath) == null) {
            curator.create().creatingParentsIfNeeded()
                            .forPath(cfg.zookeeperRootPath)
        }

        bind(classOf[CuratorFramework]).toInstance(curator)
        log.info(".. ZooKeeper is ready")

    }

}
