/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import guice._
import guice.config.{MockConfigProviderModule, ConfigProviderModule}
import guice.datapath.{MockDatapathModule, MockOvsDatapathConnectionProvider, DatapathModule}
import guice.zookeeper.ZookeeperConnectionModule
import host.guice.HostAgentModule
import org.scalatest.{BeforeAndAfter, Suite, BeforeAndAfterAll}
import com.google.inject.{AbstractModule, Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import com.midokura.config.ConfigProvider
import com.midokura.netlink.protos.OvsDatapathConnection
import reactor.ReactorModule
import services.{MidolmanActorsService, MidolmanService}
import akka.actor.{Props, ActorRef, ActorSystem}
import state.{MockDirectory, Directory}
import akka.dispatch.{Future, Await}
import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask
import com.midokura.sdn.dp.{Port, Datapath}
import scala.collection.JavaConversions._
import collection.mutable
import com.midokura.midostore.module.MidoStoreModule
import com.midokura.midostore.services.MidostoreSetupService
import com.midokura.midostore.MidostoreClient
import java.util.UUID
import akka.testkit.{TestActor, TestProbe}

trait MidolmanTestCase extends Suite with BeforeAndAfterAll with BeforeAndAfter {

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

    protected def midoStore(): MidostoreClient = {
        injector.getInstance(classOf[MidostoreClient])
    }

    protected def hostId(): UUID = {
        UUID.fromString("067e6162-3b6f-4ae2-a171-2470b63dff00")
    }

    def topActor(name: String): ActorRef = {
        actors().actorFor(actors() / name)
    }

    before {
        val config = fillConfig(new HierarchicalConfiguration())
        injector = Guice.createInjector(
//            new HostAgentModule(), // We don't need it in this test
            new MockConfigProviderModule(config),
            new MockDatapathModule(),
            new MockZooKeeperModule(),

            new ReactorModule(),
            new MidoStoreModule(),
            new MidolmanActorsModule(),
            new MidolmanModule()
        )

        injector.getInstance(classOf[MidolmanService]).startAndWait()
        injector.getInstance(classOf[MidostoreSetupService]).startAndWait()
    }

    after {
        injector.getInstance(classOf[MidolmanService]).stopAndWait()
    }

    protected def sendReply[T](actor: ActorRef, msg: Object): T = {
        val t = Timeout(1 second)
        val replyPromise: Future[Any] = ask(actor, msg)(t)

        Await.result(replyPromise, t.duration).asInstanceOf[T]
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

    private class MockZooKeeperModule extends ZookeeperConnectionModule {
        protected override def bindZookeeperConnection() {
        }

        protected override def bindDirectory() {
            bind(classOf[Directory])
                .to(classOf[MockDirectory])
                .asEagerSingleton()
        }
    }
}
