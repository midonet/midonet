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
package org.midonet.midolman.datapath

import java.util.{HashSet, UUID}

import scala.concurrent.{ExecutionContext, Future}

import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.topology.devices.BridgePort
import org.midonet.midolman.topology.rcu.PortBinding
import org.midonet.odp.DpPort
import org.midonet.odp.ports.{InternalPort, NetDevPort}
import org.midonet.util.collection.Bimap
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class DatapathPortEntanglerTest extends FlatSpec with ShouldMatchers with OneInstancePerTest {

    val controller = new DatapathPortEntangler.Controller {
        var portCreated: DpPort = _
        var portRemoved: DpPort = _
        var portUpdated: DpPort = _
        var portActive: Boolean = _
        var portNumbers = 0

        def clear(): Unit = {
            portCreated = null
            portRemoved = null
            portUpdated = null
        }

        override def addToDatapath(interfaceName: String): Future[(DpPort, Int)] = {
            portNumbers += 1
            val dpPort = DpPort.fakeFrom(new NetDevPort(interfaceName), portNumbers)
            portCreated = dpPort
            Future.successful((portCreated, portNumbers))
        }

        override def removeFromDatapath(port: DpPort): Future[Boolean] = {
            portRemoved = port
            Future.successful(true)
        }

        override def setVportStatus(port: DpPort, binding: PortBinding,
                                    isActive: Boolean): Future[_] = {
            portUpdated = port
            portActive = isActive
            Future.successful(null)
        }
    }

    val entangler = new DatapathPortEntangler {
        val controller = DatapathPortEntanglerTest.this.controller
        val log = Logger(NOPLogger.NOP_LOGGER)
        val ec = ExecutionContext.callingThread
    }

    trait DatapathOperation {
        val port: String

        def act(): Unit
        def validate(prevInterfaceToStatus: Map[String, Boolean],
                     prevInterfaceToVport: Bimap[String, UUID],
                     prevInterfaceToDpPort: Map[String, DpPort]): Unit
    }

    case class Interface(port: String, isUp: Boolean, internal: Boolean = false) extends DatapathOperation {

        override def act(): Unit = {
            val ifts = new HashSet[InterfaceDescription]()
            val ift = new InterfaceDescription(port)
            ift.setUp(isUp)
            ifts.add(ift)
            entangler.updateInterfaces(ifts)
        }

        override def validate(prevInterfaceToStatus: Map[String, Boolean],
                              prevInterfaceToVport: Bimap[String, UUID],
                              prevInterfaceToDpPort: Map[String, DpPort]): Unit = {

            entangler.interfaceToDescription(port).isUp should be (isUp)

            if ((controller.portCreated ne null) || (controller.portUpdated ne null)) {
                (prevInterfaceToVport contains port) should be (true)
                (entangler.interfaceToVport contains port) should be (true)
                (entangler.interfaceToDpPort contains port) should be (true)

                if (controller.portCreated ne null) {
                    (prevInterfaceToStatus contains port) should be (false)
                    (prevInterfaceToDpPort contains port) should be(false)
                    if (controller.portUpdated ne null) {
                        isUp should be (true)
                        controller.portActive should be (true)
                    }
                } else {
                    controller.portActive should be (isUp)
                    if (!internal) {
                        (prevInterfaceToStatus get port).get should be (!isUp)
                    }
                    (prevInterfaceToDpPort contains port) should be (true)
                }
            } else if (controller.portRemoved ne null) {
                fail("port removed on interface update")
            } else {
                (prevInterfaceToVport contains port) should be (entangler.interfaceToVport contains port)
                (prevInterfaceToDpPort contains port) should be (entangler.interfaceToDpPort contains port)
                if (!internal) {
                    (entangler.interfaceToVport contains port) should be (entangler.interfaceToDpPort contains port)
                }
            }
        }
    }

    case class InterfaceDeleted(port: String, internal: Boolean = false) extends DatapathOperation {

        override def act(): Unit = {
            entangler.updateInterfaces(new HashSet[InterfaceDescription]())
        }

        override def validate(prevInterfaceToStatus: Map[String, Boolean],
                              prevInterfaceToVport: Bimap[String, UUID],
                              prevInterfaceToDpPort: Map[String, DpPort]): Unit = {

            (entangler.interfaceToDescription contains port) should be (false)
            (entangler.interfaceToDpPort contains port) should be (internal)

            if (controller.portCreated ne null) {
                fail("port added on interface delete")
            } else if ((controller.portRemoved ne null) || (controller.portUpdated ne null)) {
                (prevInterfaceToStatus contains port) should be (true)
                (prevInterfaceToVport contains port) should be (true)
                (entangler.interfaceToVport contains port) should be (true)
                (prevInterfaceToDpPort contains port) should be (true)

                if (controller.portUpdated ne null) {
                    controller.portActive should be (false)
                    (prevInterfaceToStatus get port).get should be (true) // prev was up
                }
            } else {
                (prevInterfaceToVport contains port) should be (entangler.interfaceToVport contains port)
                (prevInterfaceToDpPort contains port) should be (entangler.interfaceToDpPort contains port)
                (prevInterfaceToDpPort contains port) should be (internal)
            }
        }
    }

    case class VportBindingAdded(port: String, uuid: UUID, internal: Boolean = false) extends DatapathOperation {

        override def act(): Unit = {
            val bridgePort = new BridgePort() {
                id = uuid
                interfaceName = port
            }
            entangler.updateVPortInterfaceBindings(Map(uuid -> PortBinding(uuid, 1L, port)))
        }

        override def validate(prevInterfaceToStatus: Map[String, Boolean],
                              prevInterfaceToVport: Bimap[String, UUID],
                              prevInterfaceToDpPort: Map[String, DpPort]): Unit = {

            (entangler.interfaceToVport get port).get should be (uuid)

            if ((controller.portCreated ne null) || (controller.portUpdated ne null)) {
                (prevInterfaceToStatus contains port) should be (true)
                (entangler.interfaceToDescription contains port) should be (true)
                if (!internal) {
                    (prevInterfaceToDpPort contains port) should be (false)
                }
                (entangler.interfaceToDpPort contains port) should be (true)

                if (controller.portUpdated ne null) {
                    controller.portActive should be (true)
                    (prevInterfaceToStatus get port).get should be (true)
                } else {
                    (prevInterfaceToStatus get port).get should be (false)
                }
            } else if (controller.portRemoved ne null) {
                fail("port removed on new binding")
            } else {
                (prevInterfaceToStatus get port) should be (for {
                    ifDesc <- entangler.interfaceToDescription.get(port)
                } yield ifDesc.isUp)
                (prevInterfaceToDpPort contains port) should be (
                    entangler.interfaceToDpPort contains port)
            }
        }
    }

    case class VportBindingDeleted(port: String, internal: Boolean = false) extends DatapathOperation {

        override def act(): Unit = {
            entangler.updateVPortInterfaceBindings(Map())
        }

        override def validate(prevInterfaceToStatus: Map[String, Boolean],
                              prevInterfaceToVport: Bimap[String, UUID],
                              prevInterfaceToDpPort: Map[String, DpPort]): Unit = {

            (entangler.interfaceToVport contains port) should be (false)
            (entangler.interfaceToDpPort contains port) should be (internal)

            if (controller.portCreated ne null) {
                fail("port created on binding delete")
            } else if ((controller.portRemoved ne null) || (controller.portUpdated ne null)) {
                (prevInterfaceToVport contains port) should be (true)
                (prevInterfaceToStatus contains port) should be (true)
                (entangler.interfaceToDescription contains port) should be (true)
                (prevInterfaceToDpPort contains port) should be (true)

                if (controller.portUpdated ne null) {
                    controller.portActive should be (false)
                    (prevInterfaceToStatus get port).get should be (true) // prev was up
                }
            } else {
                (prevInterfaceToStatus get port) should be (for {
                    ifDesc  <- entangler.interfaceToDescription.get(port)
                } yield ifDesc.isUp)

                (prevInterfaceToDpPort contains port) should be (
                    entangler.interfaceToDpPort contains port)
            }
        }
    }

    "Operations on an interface and port binding" should "be consistent" in {
        val uuid = UUID.randomUUID()
        val history = List(Interface("eth0", true), Interface("eth0", true),
             Interface("eth0", false), Interface("eth0", false),
             InterfaceDeleted("eth0"), InterfaceDeleted("eth0"),
             VportBindingAdded("eth0", uuid), VportBindingAdded("eth0", uuid),
             VportBindingDeleted("eth0"), VportBindingDeleted("eth0")).permutations.flatten
        validateHistory(history)
    }

    "Operations on an internal DP port" should "be consistent" in {
        val uuid = UUID.randomUUID()
        val dpPort = DpPort.fakeFrom(new InternalPort("midonet"), 0).asInstanceOf[InternalPort]
        entangler.registerInternalPort(dpPort)
        val history = List(Interface("midonet", true, true), Interface("midonet", true, true),
                           Interface("midonet", false, true), Interface("midonet", false, true),
                           InterfaceDeleted("midonet", true), InterfaceDeleted("midonet", true),
                           VportBindingAdded("midonet", uuid, true), VportBindingAdded("midonet", uuid, true),
                           VportBindingDeleted("midonet", true), VportBindingDeleted("midonet", true)).permutations.flatten
        validateHistory(history)
    }

    def validateHistory(history: Iterator[DatapathOperation]): Unit = {
        var i = 0
        try {
            history foreach { op =>
                i += 1
                val prevInterfaceToStatus =
                    entangler.interfaceToDescription.mapValues(_.isUp)
                val prevInterfaceToVport = entangler.interfaceToVport
                val prevInterfaceToDpPort = entangler.interfaceToDpPort
                op.act()
                op.validate(prevInterfaceToStatus, prevInterfaceToVport, prevInterfaceToDpPort)
                controller.clear()
            }
        } catch { case e: Throwable =>
            throw new Exception(s"Failed with history = ${history.take(i).toList}", e)
        }
    }

    "A failed DP port create operation" should "be retried" in {
        val failOnceController = new DatapathPortEntangler.Controller {
            var shouldFail = true
            var portActive = false

            override def addToDatapath(interfaceName: String): Future[(DpPort, Int)] =
                if (shouldFail) {
                    shouldFail = false
                    Future.failed(new Exception)
                } else {
                    val dpPort = DpPort.fakeFrom(new NetDevPort(interfaceName), 1)
                    Future.successful((dpPort, 1))
                }

            override def removeFromDatapath(port: DpPort): Future[Boolean] = {
                Future.successful(true)
            }

            override def setVportStatus(port: DpPort, binding: PortBinding,
                                        isActive: Boolean): Future[_] = {
                portActive = isActive
                Future.successful(null)
            }
        }

        val entangler = new DatapathPortEntangler {
            val controller = failOnceController
            val log = Logger(NOPLogger.NOP_LOGGER)
            val ec = ExecutionContext.callingThread
        }

        val id = UUID.randomUUID()
        val binding = new PortBinding(id, 1, "eth1")
        val desc = new InterfaceDescription("eth1")
        entangler.updateVPortInterfaceBindings(Map(id -> binding))
        entangler.updateInterfaces(new HashSet[InterfaceDescription] { add(desc) })

        controller.portActive should be (false)
        entangler.interfaceToDescription("eth1") should be (desc)
        entangler.bindings("eth1") should be (binding)
        entangler.interfaceToVport.get("eth1") should be (Some(id))
        entangler.dpPortNumToInterface.get(1) should be (None)
        entangler.interfaceToDpPort.get("eth1") should be (None)

        entangler.updateInterfaces(new HashSet[InterfaceDescription] { add(desc) })

        entangler.dpPortNumToInterface(1) should be ("eth1")
        entangler.interfaceToDpPort("eth1") should be (
            DpPort.fakeFrom(new NetDevPort("eth1"), 1))
    }
}
