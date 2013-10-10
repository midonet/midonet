/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.midolman

import scala.collection.JavaConversions._
import java.util.UUID
import java.util.concurrent.TimeUnit

import akka.actor._
import akka.testkit._
import akka.util.{Duration, Timeout}
import akka.util.duration._

import com.google.inject._
import org.apache.commons.configuration.HierarchicalConfiguration
import org.scalatest._
import org.scalatest.matchers.ShouldMatchers
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.midolman.guice._
import org.midonet.midolman.guice.cluster.ClusterClientModule
import org.midonet.midolman.guice.config.MockConfigProviderModule
import org.midonet.midolman.guice.datapath.MockDatapathModule
import org.midonet.midolman.guice.reactor.MockReactorModule
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule
import org.midonet.midolman.services.{MidolmanService, MockMidolmanActorsService, MidolmanActorsService, HostIdProviderService}
import org.midonet.cluster.services.MidostoreSetupService
import org.midonet.odp._
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.protos.MockOvsDatapathConnection
import org.midonet.odp.protos.MockOvsDatapathConnection.FlowListener
import org.midonet.util.functors.callbacks.AbstractCallback
import org.midonet.midolman.version.guice.VersionModule
import org.midonet.midolman.guice.serialization.SerializationModule
import org.midonet.midolman.host.scanner.InterfaceScanner


class SingleActorTestCase extends Suite with FeatureSpec with BeforeAndAfter with
                                  ShouldMatchers with OneInstancePerTest with
                                  GivenWhenThen {

    case class PacketsExecute(packet: Packet)
    case class FlowAdded(flow: Flow)
    case class FlowRemoved(flow: Flow)

    private val log: Logger = LoggerFactory.getLogger(classOf[MidolmanTestCase])

    var actorsService: MockMidolmanActorsService = null
    implicit def actorSystem: ActorSystem = actorsService.system

    var injector: Injector = null

    var flowAddedProbe: TestProbe = null
    var flowRemovedProbe: TestProbe = null
    var packetOutProbe: TestProbe = null

    val timeout = Duration(3, TimeUnit.SECONDS)
    implicit val askTimeout = Timeout(3 second)

    protected def fillConfig(config: HierarchicalConfiguration)
            : HierarchicalConfiguration = {
        config.setProperty("midolman.midolman_root_key", "/test/v3/midolman")
        config.setProperty("midolman.enable_monitoring", "false")
        config.setProperty("cassandra.servers", "localhost:9171")
        config
    }

    protected def mockDpConn: MockOvsDatapathConnection = {
        injector.getInstance(classOf[OvsDatapathConnection])
            .asInstanceOf[MockOvsDatapathConnection]
    }

    private def injectedInstance[T <: Actor](f: () => T): T = {
        val instance = f()
        injector.injectMembers(instance)
        instance
    }

    protected def actorFor[T <: Actor](f: () => T): TestActorRef[T] =
        TestActorRef[T](Props(() => injectedInstance(f)))(actorSystem)

    before {
        try {
            val config = fillConfig(new HierarchicalConfiguration())
            injector = Guice.createInjector(getModulesAsJavaIterable(config))
            actorsService = injector.getInstance(classOf[MidolmanActorsService])
                .asInstanceOf[MockMidolmanActorsService]

            injector.getInstance(classOf[MidostoreSetupService]).startAndWait()
            injector.getInstance(classOf[MidolmanService]).startAndWait()

            mockDpConn.packetsExecuteSubscribe(
                new AbstractCallback[Packet, Exception] {
                    override def onSuccess(pkt: Packet) {
                        actorSystem.eventStream.publish(PacketsExecute(pkt))
                    }
                })

            mockDpConn.flowsSubscribe(
                new FlowListener {
                    def flowCreated(flow: Flow) {
                        actorSystem.eventStream.publish(FlowAdded(flow))
                    }

                    def flowDeleted(flow: Flow) {
                        actorSystem.eventStream.publish(FlowRemoved(flow))
                    }
                })

            beforeTest()
        } catch {
            case e: Throwable => fail(e)
        }
    }

    after {
        afterTest()
    }

    // These methods can be overridden by each class mixing MidolmanTestCase
    // to add custom operations before each test and after each tests
    protected def beforeTest() {}

    protected def afterTest() {}

    protected def getModulesAsJavaIterable(config: HierarchicalConfiguration)
            : java.lang.Iterable[Module] = {
        asJavaIterable(getModules(config))
    }

    protected def getModules(config: HierarchicalConfiguration)
            : List[Module] = {
        List[Module](
            new VersionModule(),
            new SerializationModule(),
            new MockConfigProviderModule(config),
            new MockDatapathModule(),
            new MockCacheModule(),
            new MockZookeeperConnectionModule(),
            new AbstractModule {
                def configure() {
                    bind(classOf[HostIdProviderService])
                        .toInstance(new HostIdProviderService() {
                        val hostId = UUID.randomUUID()
                        def getHostId: UUID = hostId
                    })
                }
            },
            new MockReactorModule(),
            new MockMonitoringStoreModule(),
            new ClusterClientModule(),
            new MockMidolmanActorsModule(),
            new MidolmanModule(),
            new PrivateModule {
                override def configure() {
                    bind(classOf[InterfaceScanner])
                        .to(classOf[MockInterfaceScanner]).asEagerSingleton()
                    expose(classOf[InterfaceScanner])
                }
            }
        )
    }

    class MockMidolmanActorsModule() extends MidolmanActorsModule {
        protected override def bindMidolmanActorsService() {
            bind(classOf[MidolmanActorsService])
                .toInstance(new MockMidolmanActorsService())
        }
    }
}

