/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman

import guice.{MidolmanActorsModule, MockOvsDatapathConnectionProvider, MidolmanModule}
import org.scalatest.{BeforeAndAfter, Suite, BeforeAndAfterAll}
import com.google.inject.{AbstractModule, Guice, Injector}
import org.apache.commons.configuration.HierarchicalConfiguration
import com.midokura.config.ConfigProvider
import com.midokura.netlink.protos.OvsDatapathConnection
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
            new AbstractModule {
                def configure() {
                    bind(classOf[ConfigProvider])
                        .toInstance(ConfigProvider.providerForIniConfig(config))
                }
            },
            new MidolmanModule {
                protected override def bindZookeeperConnection() {
                    // not needed here
                }

                // override the binding for the ovsDatapathConnection to be in-memory
                protected override def bindOvsDatapathConnection() {
                    bind(classOf[OvsDatapathConnection])
                        .toProvider(classOf[MockOvsDatapathConnectionProvider])
                        .asEagerSingleton()
                }

                // override the binding for the directory to be in-memory
                protected override def bindDirectory() {
                    bind(classOf[Directory])
                        .to(classOf[MockDirectory])
                        .asEagerSingleton()
                }
            },
            new MidoStoreModule(),
            new MidolmanActorsModule() {
                protected override def bindMidolmanActorsService() {
                    bind(classOf[MidolmanActorsService])
                        .toInstance(
                        new MidolmanActorsService() {
                            protected override def startActor(actorProps: Props, actorName: String) {
                                val actor = system().actorOf(actorProps)
                                val probe = TestProbe()
                                system().actorOf(Props(), actorName)

                                probe.setAutoPilot(new TestActor.AutoPilot {
                                    def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
                                        msg match {
                                            case x ⇒ actor.tell(x, sender); TestActor.KeepRunning
                                        }
                                })
                            }
                        })
                }
            }
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
}
