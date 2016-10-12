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

import org.rogach.scallop._

import com.codahale.metrics.MetricRegistry

import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.ExponentialBackoffRetry

import rx.{Observer, Scheduler, Subscription}

import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator}
import org.midonet.cluster.services.MidonetBackendService
import org.midonet.cluster.state.PortStateStorage.asPort
import org.midonet.midolman.ShardedSimulationBackChannel
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.host.services.HostService
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.topology.devices.Host
import org.midonet.netlink.rtnetlink.{Addr, Link, Neigh, Route}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.eventloop.TryCatchReactor
import org.midonet.util.functors.makeAction1

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

    val curator = CuratorFrameworkFactory.newClient(
        opts.zk.get.get,
        new ExponentialBackoffRetry(1000, 10))
    curator.start()

    System.setProperty("midonet.host_id_filepath",
                       opts.hostIdFile.get.get)
    val hostId = HostIdGenerator.getHostId
    val executor = new SameThreadButAfterExecutorService

    val metrics = new MetricRegistry
    val configurator = MidoNodeConfigurator(curator)
    val config = new MidolmanConfig(configurator.runtimeConfig(hostId),
                                    configurator.mergedSchemas())
    val backendConfig = config.zookeeper
    val reactor = new TryCatchReactor("zookeeper", 1);
    val backendService = new MidonetBackendService(
        backendConfig, curator, curator, metrics, None)
    backendService.startAsync
    val hostService = new HostService(config, backendConfig,
                                      backendService,
                                      new NullInterfaceScanner, hostId,
                                      reactor);

    val backChannel = new ShardedSimulationBackChannel
    val vt = new VirtualTopology(backendService,
                                 config,
                                 backChannel,
                                 null,
                                 metrics,
                                 executor,
                                 executor,
                                 () => true)


    hostService.startAsync()

    VirtualTopology.observable(classOf[Host], hostId)
        .subscribe(makeAction1 { (host: Host) =>
                       println(s"Update for host $host")
                       var i = 1
                       host.portBindings.keys map { portId =>
                           println(s"Setting port $portId as active")
                           backendService.stateStore.setPortActive(
                               portId, hostId, true, i)
                           i += 1
                       }
                   })

    hostService.synchronized {
        hostService.wait()
    }
}

class NullInterfaceScanner extends InterfaceScanner {
    override def subscribe(obs: Observer[Set[InterfaceDescription]],
                           scheduler: Option[Scheduler] = None): Subscription = {
        obs.onNext(Set.empty)
        new Subscription() {
            private var unsubscribed = false
            override def isUnsubscribed = unsubscribed
            override def unsubscribe: Unit = { unsubscribed = true }
        }
    }
    override def routesCreate(dst: IPv4Addr, prefix: Int,
                              gw: IPv4Addr, link: Link,
                              observer: Observer[Boolean]): Unit = {}
    override def linksSet(link: Link, observer: Observer[Boolean]): Unit = {}
    override def linksList(observer: Observer[Set[Link]]): Unit = {}
    override def neighsList(observer: Observer[Set[Neigh]]): Unit = {}
    override def linksGet(ifindex: Int, observer: Observer[Link]): Unit = {}
    override def routesGet(dst: IPv4Addr, observer: Observer[Route]): Unit = {}
    override def linksSetAddr(link: Link, mac: MAC,
                              observer: Observer[Boolean]): Unit = {}
    override def routesList(observer: Observer[Set[Route]]): Unit = {}
    override def addrsList(observer: Observer[Set[Addr]]): Unit = {}
    override def linksCreate(link: Link, observer: Observer[Link]): Unit = {}

    override def start(): Unit = {}
    override def stop(): Unit = {}
}

