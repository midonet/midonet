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
package org.midonet.services

import java.io.{File, FileReader, FileWriter}
import java.util.{Properties, UUID}

import scala.collection.JavaConverters._

import org.rogach.scallop._
import com.google.common.collect.Ordering

import org.midonet.midolman.cluster.serialization.JsonVersionZkSerializer
import org.midonet.midolman.host.state.{HostDirectory, HostZkManager}
import org.midonet.midolman.state.{Directory, PathBuilder}
import org.midonet.midolman.state.SessionUnawareConnectionWatcher
import org.midonet.midolman.state.{ZkConnection, ZkManager, ZkSystemDataProvider}
import org.midonet.midolman.state.zkManagers.PortZkManager
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.util.eventloop.TryCatchReactor

/**
  * A fake agent which will register itself as a host and set any
  * ports bound to it as active.
  *
  * This is useful for testing, to have another agent send packets over the
  * overlay without having a full agent in place.
  */
object FakeAgent extends App {
    val opts = new ScallopConf(args) {
        val zk = opt[String](
            "zk",
            descr = s"Zookeeper connection string")
        val hostIdFile = opt[String](
            "hostIdFile",
            descr = s"File with UUID of host")
        val help = opt[Boolean]("help", noshort = true,
                                descr = "Show this message")
    }

    val reactor = new TryCatchReactor("zookeeper", 1)
    val zkConnection = new ZkConnection(
        opts.zk.get.get, 60000,
        new SessionUnawareConnectionWatcher(), reactor)
    zkConnection.open()

    val pathBuilder = new PathBuilder("/midonet/v1")
    val zkManager = new ZkManager(zkConnection.getRootDirectory(),
                                  "/midonet/v1")

    val serializer = new JsonVersionZkSerializer(
        new ZkSystemDataProvider(zkManager, pathBuilder,
                                 Ordering.natural()),
        Ordering.natural())
    val hostManager = new HostZkManager(zkManager, pathBuilder,
                                        serializer)
    val portManager = new PortZkManager(zkManager, pathBuilder,
                                        serializer)

    val idFile = new File(opts.hostIdFile.get.get)
    val properties = new Properties
    if (!idFile.exists()) {
        properties.setProperty("host_uuid", UUID.randomUUID.toString)
        properties.store(new FileWriter(idFile), "")
    } else {
        properties.load(new FileReader(idFile))
    }

    val hostId = UUID.fromString(properties.getProperty("host_uuid"))
    val metadata = new HostDirectory.Metadata
    metadata.setName("fakeagent")

    try {
        hostManager.createHost(hostId, metadata)
    } catch {
        case t: Throwable => println(
            "Error creating host, must already exist")
    }
    hostManager.makeAlive(hostId)

    val portWatcher = new Directory.DefaultTypedWatcher() {
        override def pathChildrenUpdated(path: String): Unit = {}
    }
    var lastBindings = Set[HostDirectory.VirtualPortMapping]()
    var tunnelKeys = 0
    while (true) {
        Thread.sleep(1000)
        val bindings = hostManager.getVirtualPortMappings(
            hostId, portWatcher).asScala.toSet
        val diff = bindings -- lastBindings
        for (newBinding <- diff) {
            val port = newBinding.getVirtualPortId
            println(s"Setting $port active")
            val tunnelKey = tunnelKeys
            tunnelKeys += 1
            portManager.setActivePort(port,
                                      hostId, true,
                                      tunnelKeys)
        }
        lastBindings = bindings
    }
}

