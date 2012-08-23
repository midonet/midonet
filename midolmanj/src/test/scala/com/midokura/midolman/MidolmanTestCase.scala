/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import scala.collection.JavaConversions._
import scala.collection.mutable
import java.util.UUID

import akka.testkit._
import akka.actor._
import akka.dispatch.{Future, Await}
import akka.util.Timeout
import akka.util.duration._
import guice._
import guice.actors.{OutgoingMessage, TestableMidolmanActorsModule}
import guice.config.MockConfigProviderModule
import guice.datapath.MockDatapathModule
import guice.zookeeper.MockZookeeperConnectionModule

import com.google.inject.{AbstractModule, Module, Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.{OneInstancePerTest, BeforeAndAfter, Suite,
                      BeforeAndAfterAll}

import com.midokura.midolman.DatapathController.{InitializationComplete,
                                                 Initialize}
import com.midokura.midolman.services.{MidolmanActorsService, MidolmanService}
import com.midokura.midolman.guice.cluster.ClusterClientModule
import com.midokura.midolman.guice.reactor.ReactorModule
import com.midokura.midonet.cluster.Client
import com.midokura.midonet.cluster.services.MidostoreSetupService
import com.midokura.netlink.protos.OvsDatapathConnection
import com.midokura.netlink.protos.mocks.MockOvsDatapathConnectionImpl
import com.midokura.sdn.dp.{Packet, Port, Datapath}


trait MidolmanTestCase extends Suite with BeforeAndAfterAll
             with BeforeAndAfter with OneInstancePerTest with ShouldMatchers {

    var injector: Injector = null

    protected def fillConfig(config: HierarchicalConfiguration): HierarchicalConfiguration = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config
    }

    protected def dpConn(): OvsDatapathConnection = {
        injector.getInstance(classOf[OvsDatapathConnection])
    }

    protected def actors(): ActorSystem = {
        injector.getInstance(classOf[MidolmanActorsService]).system
    }

    protected def midoStore(): Client = {
        injector.getInstance(classOf[Client])
    }

    protected def hostId(): UUID = {
        UUID.fromString("067e6162-3b6f-4ae2-a171-2470b63dff00")
    }

    before {
        val config = fillConfig(new HierarchicalConfiguration())
        injector = Guice.createInjector(
            asJavaIterable(getModules(config))
        )

        injector.getInstance(classOf[MidolmanService]).startAndWait()
        injector.getInstance(classOf[MidostoreSetupService]).startAndWait()
    }

    after {
        injector.getInstance(classOf[MidolmanService]).stopAndWait()
    }

    val probesByName = mutable.Map[String, TestKit]()
    val actorsByName = mutable.Map[String, TestActorRef[Actor]]()

    protected def getModules(config: HierarchicalConfiguration): Iterable[Module] = {
        List(
            new MockConfigProviderModule(config),
            new MockDatapathModule(),
            new MockZookeeperConnectionModule(),

            new ReactorModule(),
            new ClusterClientModule(),
            new TestableMidolmanActorsModule(probesByName, actorsByName),
            new MidolmanModule()
        )
    }

    def topActor[A <: Actor](name: String): ActorRef = {
        actors().actorFor(actors() / name)
    }

    protected def ask[T](actor: ActorRef, msg: Object): T = {
        val t = Timeout(1 second)
        val promise= akka.pattern.ask(actor, msg)(t).asInstanceOf[Future[T]]

        Await.result(promise, t.duration)
    }

    def datapathPorts(datapath: Datapath): mutable.Map[String, Port[_, _]] = {

        val ports: mutable.Set[Port[_, _]] =
            dpConn().portsEnumerate(datapath).get()

        val portsByName = mutable.Map[String, Port[_, _]]()
        for (port <- ports) {
            portsByName.put(port.getName, port)
        }

        portsByName
    }

    protected def probeByName(name:String):TestKit = {
        probesByName(name)
    }

    protected def actorByName[A <: Actor](name: String):TestActorRef[A] = {
        actorsByName(name).asInstanceOf[TestActorRef[A]]
    }

    protected def initializeDatapath(): DatapathController.InitializationComplete = {
        val result = ask[InitializationComplete](
            topActor(DatapathController.Name), DatapathController.Initialize())

        dpProbe().expectMsgType[Initialize] should not be null
        dpProbe().expectMsgType[OutgoingMessage] should not be null

        result
    }

    protected def triggerPacketIn(packet:Packet) {
        dpConn().asInstanceOf[MockOvsDatapathConnectionImpl].triggerPacketIn(packet)
    }

    protected def dpController(): TestActorRef[DatapathController] = {
        actorByName(DatapathController.Name)
    }

    protected def dpProbe(): TestKit = {
        probeByName(DatapathController.Name)
    }

    protected def flowProbe(): TestKit = {
        probeByName(FlowController.Name)
    }

    protected def simProbe(): TestKit = {
        probeByName(SimulationController.Name)
    }

}
