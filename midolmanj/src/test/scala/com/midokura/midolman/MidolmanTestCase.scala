/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import guice._
import cluster.ClusterClientModule
import guice.config.MockConfigProviderModule
import guice.datapath.MockDatapathModule
import guice.zookeeper.MockZookeeperConnectionModule
import org.scalatest.{BeforeAndAfter, Suite, BeforeAndAfterAll}
import com.google.inject.{AbstractModule, Module, Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import com.midokura.netlink.protos.OvsDatapathConnection
import reactor.ReactorModule
import services.{MidolmanActorsService, MidolmanService}
import akka.actor._
import akka.dispatch.{Future, Await}
import akka.util.Timeout
import akka.util.duration._
import akka.pattern.ask
import com.midokura.sdn.dp.{Port, Datapath}
import scala.collection.JavaConversions._
import collection.mutable
import com.midokura.midonet.cluster.Client
import java.util.UUID
import com.midokura.midonet.cluster.services.MidostoreSetupService
import akka.testkit.{TestProbe, TestActorRef}

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

    protected def getModules(config: HierarchicalConfiguration): Iterable[Module] = {
        List(
            new MockConfigProviderModule(config),
            new MockDatapathModule(),
            new MockZookeeperConnectionModule(),

            new ReactorModule(),
            new ClusterClientModule(),
            new MidolmanModule(),
            new AbstractModule {
                def configure() {
                    bind(classOf[MidolmanActorsService])
                        .toInstance(new TestActorsService())
                }
            }
        )
    }

    def topActor[A <: Actor](name: String): TestActorRef[A] = {
        actors().actorFor(actors() / name).asInstanceOf[TestActorRef[A]]
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

    class TestActorsService extends MidolmanActorsService {
        protected override def makeActorRef(actorProps: Props, actorName: String): ActorRef = {
            implicit val system = actorSystem
            val probe = TestProbe()
            TestActorRef(actorProps, actorName)
//            system.actorOf(new Props(new UntypedActorFactory {
//                def create(): Actor = {
//                    probe.testActor
//                }
//            }), actorName)
//            probe.ref
        }
    }
}
