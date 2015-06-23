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

import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{ExecutionContext, Future}

import org.midonet.midolman.DatapathStateDriver
import org.midonet.midolman.DatapathStateDriver.DpTriad
import org.slf4j.helpers.NOPLogger
import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.midolman.topology.rcu.PortBinding
import org.midonet.odp.{Datapath, DpPort}
import org.midonet.odp.ports.{InternalPort, NetDevPort}
import org.midonet.util.concurrent._

@RunWith(classOf[JUnitRunner])
class DatapathPortEntanglerTest extends FlatSpec with ShouldMatchers with OneInstancePerTest {

    val entangler = new DatapathPortEntangler {
        val log = Logger(NOPLogger.NOP_LOGGER)
        override protected val singleThreadExecutionContext =
            ExecutionContext.callingThread

        protected val driver = new DatapathStateDriver(new Datapath(0, "midonet"))

        var portCreated: DpPort = _
        var portRemoved: DpPort = _
        var portActive: Boolean = _
        var portNumbers = 0

        def clear(): Unit = {
            portCreated = null
            portRemoved = null
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

        override def setVportStatus(port: DpPort, vport: UUID, tunnelKey: Long,
                                    isActive: Boolean): Unit = {
            portActive = isActive
        }
    }

    trait DatapathOperation {
        val port: String

        def act(): Unit
        def validate(prevInterfaceToTriad: Map[String, DpTriad]): Unit
    }

    case class Interface(port: String, isUp: Boolean, internal: Boolean = false) extends DatapathOperation {

        override def act(): Unit = {
            val ift = new InterfaceDescription(port)
            ift.setUp(isUp)
            entangler.updateInterface(ift)
        }

        override def validate(prevInterfaceToTriad: Map[String, DpTriad]): Unit = {
            val newTriad = entangler.interfaceToTriad.get(port)
            val oldTriad = prevInterfaceToTriad.get(port).orNull

            if (entangler.portCreated ne null) {
                isUp should be (true)

                oldTriad.isUp should be (false)
                oldTriad.vport should not be null
                if (internal) {
                    oldTriad.dpPort should not be null
                    oldTriad.dpPortNo should not be null
                } else {
                    oldTriad.dpPort should be (null)
                    oldTriad.dpPortNo should be (null)
                }

                newTriad.isUp should be (isUp)
                newTriad.vport should be (oldTriad.vport)
                newTriad.dpPort should not be null
                newTriad.dpPortNo should not be null

                entangler.dpPortNumToTriad containsKey newTriad.dpPortNo should be (true)
                entangler.vportToTriad containsKey newTriad.vport should be (true)
                entangler.keyToTriad containsKey newTriad.tunnelKey should be (true)

                entangler.portActive should be (true)
            } else if (entangler.portRemoved ne null) {
                isUp should be (false)

                oldTriad.isUp should be (true)
                oldTriad.vport should not be null
                oldTriad.dpPort should not be null
                oldTriad.dpPortNo should not be null

                newTriad.vport should not be null
                newTriad.isUp should be (false)

                if (internal) {
                    newTriad.dpPort should not be null
                    newTriad.dpPortNo should not be null
                    entangler.interfaceToTriad containsKey port should be (true)
                    entangler.dpPortNumToTriad containsKey newTriad.dpPortNo should be (true)
                } else if (newTriad eq null) {
                    newTriad.dpPort should be (null)
                    newTriad.dpPortNo should be (null)
                    entangler.interfaceToTriad containsKey port should be (false)
                    entangler.dpPortNumToTriad containsKey oldTriad.dpPortNo should be (false)
                }

                entangler.vportToTriad containsKey newTriad.vport should be (true)
                entangler.keyToTriad containsKey newTriad.tunnelKey should be (false)

                entangler.portActive should be (false)
            } else {
                (oldTriad, newTriad) match {
                    case (null, null) =>
                        isUp should be (false)
                        entangler.interfaceToTriad containsKey port should be (false)
                    case (null, _) =>
                        isUp should be (true)
                        newTriad.isUp should be (true)
                        newTriad.dpPort should be (null)
                        entangler.interfaceToTriad containsKey port should be (true)
                        newTriad.vport should be (null)
                    case (_, null) =>
                        oldTriad.vport should be (null)
                        oldTriad.dpPort should be (null)
                        entangler.interfaceToTriad containsKey port should be (false)
                    case (_, _) =>
                        newTriad.isUp should be (isUp)
                        oldTriad.vport should be (newTriad.vport)
                        oldTriad.dpPort should be (newTriad.dpPort)
                        if (!internal)
                            oldTriad.dpPortNo should be (newTriad.dpPortNo)
                }
            }
        }
    }

    case class InterfaceDeleted(port: String, internal: Boolean = false) extends DatapathOperation {

        override def act(): Unit = {
            entangler.deleteInterface(new InterfaceDescription(port))
        }

        override def validate(prevInterfaceToTriad: Map[String, DpTriad]): Unit = {
            val newTriad = entangler.interfaceToTriad.get(port)
            val oldTriad = prevInterfaceToTriad.get(port).orNull

            if (entangler.portCreated ne null) {
                fail("port added on interface delete")
            } else if (entangler.portRemoved ne null) {
                oldTriad.isUp should be (true)
                oldTriad.vport should not be null
                oldTriad.dpPort should not be null
                oldTriad.dpPortNo should not be null

                newTriad.isUp should be (false)
                newTriad.vport should not be null

                if (internal) {
                    newTriad.dpPort should not be null
                    newTriad.dpPortNo should not be null
                    entangler.interfaceToTriad containsKey port should be (true)
                    entangler.dpPortNumToTriad containsKey newTriad.dpPortNo should be (true)
                } else if (newTriad eq null) {
                    newTriad.dpPort should be (null)
                    newTriad.dpPortNo should be (null)
                    entangler.interfaceToTriad containsKey port should be (false)
                    entangler.dpPortNumToTriad containsKey oldTriad.dpPortNo should be (false)
                }

                entangler.vportToTriad containsKey newTriad.vport should be (true)
                entangler.keyToTriad containsKey newTriad.tunnelKey should be (false)

                entangler.portActive should be (false)
            } else {
                (oldTriad, newTriad) match {
                    case (null, null) =>
                        entangler.interfaceToTriad containsKey port should be (false)
                    case (null, _) =>
                        fail("interface delete created an entry for the interface")
                    case (_, null) =>
                        oldTriad.isUp should be (true)
                        oldTriad.vport should be (null)
                        oldTriad.dpPort should be (null)
                        entangler.interfaceToTriad containsKey port should be (false)
                    case (_, _) =>
                        newTriad.isUp should be (false)
                        if (!internal) {
                            oldTriad.isUp should be (newTriad.isUp)
                            oldTriad.vport should not be null
                        }
                        oldTriad.vport should be (newTriad.vport)
                        oldTriad.dpPort should be (newTriad.dpPort)
                        if (!internal)
                            oldTriad.dpPortNo should be (newTriad.dpPortNo)
                }
            }
        }
    }

    case class VportBindingAdded(port: String, uuid: UUID, internal: Boolean = false) extends DatapathOperation {

        override def act(): Unit = {
            entangler.updateVPortInterfaceBindings(Map(uuid -> PortBinding(uuid, 1L, port)))
        }

        override def validate(prevInterfaceToTriad: Map[String, DpTriad]): Unit = {
            val newTriad = entangler.interfaceToTriad.get(port)
            val oldTriad = prevInterfaceToTriad.get(port).orNull

            if (entangler.portCreated ne null) {
                oldTriad.isUp should be (true)
                oldTriad.vport should be (null)
                if (internal) {
                    oldTriad.dpPort should not be null
                    oldTriad.dpPortNo should not be null
                } else {
                    oldTriad.dpPort should be (null)
                    oldTriad.dpPortNo should be (null)
                }

                newTriad.isUp should be (true)
                newTriad.vport should be (uuid)
                newTriad.dpPort should not be null
                newTriad.dpPortNo should not be null

                entangler.dpPortNumToTriad containsKey newTriad.dpPortNo should be (true)
                entangler.vportToTriad containsKey newTriad.vport should be (true)
                entangler.keyToTriad containsKey newTriad.tunnelKey should be (true)

                entangler.portActive should be (true)
            } else if (entangler.portRemoved ne null) {
                fail("port removed on new binding")
            } else {
                (oldTriad, newTriad) match {
                    case (null, null) =>
                        fail("new binding didn't create an entry")
                    case (null, _) =>
                        newTriad.vport should not be null
                        newTriad.dpPort should be (null)
                        newTriad.dpPortNo should be (null)
                        entangler.vportToTriad containsKey newTriad.vport should be (true)
                        entangler.keyToTriad containsKey newTriad.tunnelKey should be (false)
                    case (_, null) =>
                        fail("new binding deleted an entry")
                    case (_, _) =>
                        oldTriad.isUp should be (newTriad.isUp)
                        if (!internal)
                            oldTriad.vport should be (newTriad.vport)

                        oldTriad.dpPort should be (newTriad.dpPort)
                        if (!internal)
                            oldTriad.dpPortNo should be (newTriad.dpPortNo)
                }
            }
        }
    }

    case class VportBindingDeleted(port: String, internal: Boolean = false) extends DatapathOperation {

        override def act(): Unit = {
            entangler.updateVPortInterfaceBindings(Map())
        }

        override def validate(prevInterfaceToTriad: Map[String, DpTriad]): Unit = {
            val newTriad = entangler.interfaceToTriad.get(port)
            val oldTriad = prevInterfaceToTriad.get(port).orNull

            if (entangler.portCreated ne null) {
                fail("port created on new deleted binding")
            } else if (entangler.portRemoved ne null) {
                oldTriad.isUp should be (true)
                oldTriad.vport should not be null
                oldTriad.dpPort should not be null
                oldTriad.dpPortNo should not be null

                newTriad.isUp should be (true)
                newTriad.vport should be (null)

                if (internal) {
                    newTriad.dpPort should not be null
                    newTriad.dpPortNo should not be null
                    entangler.interfaceToTriad containsKey port should be (true)
                    entangler.dpPortNumToTriad containsKey newTriad.dpPortNo should be (true)
                } else if (newTriad eq null) {
                    newTriad.dpPort should be (null)
                    newTriad.dpPortNo should be (null)
                    entangler.interfaceToTriad containsKey port should be (false)
                    entangler.dpPortNumToTriad containsKey oldTriad.dpPortNo should be (false)
                }

                entangler.vportToTriad containsKey port should be (false)
                entangler.keyToTriad containsKey newTriad.tunnelKey should be (false)

                entangler.portActive should be (false)
            } else {
                (oldTriad, newTriad) match {
                    case (null, null) =>
                        entangler.interfaceToTriad containsKey port should be (false)
                    case (null, _) =>
                        fail("deleting binding created an entry")
                    case (_, null) =>
                        oldTriad.isUp should be (false)
                        oldTriad.vport should not be null
                        oldTriad.dpPort should be (null)
                        entangler.interfaceToTriad containsKey port should be (false)
                    case (_, _) =>
                        oldTriad.isUp should be (newTriad.isUp)
                        if (!internal)
                            oldTriad.vport should be (newTriad.vport)
                        oldTriad.dpPort should be (newTriad.dpPort)
                        if (!internal)
                            oldTriad.dpPortNo should be (newTriad.dpPortNo)
                }
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
        var arrayBuffer = new ArrayBuffer[DatapathOperation]
        try {
            history foreach { op =>
                arrayBuffer += op
                val prevInterfaceToTriad = (entangler.interfaceToTriad.values() map {
                    case triad: DpTriad =>
                        val newTriad = DpTriad(triad.ifname)
                        newTriad.isUp = triad.isUp
                        newTriad.vport = triad.vport
                        newTriad.tunnelKey = triad.tunnelKey
                        newTriad.dpPort = triad.dpPort
                        newTriad.dpPortNo = triad.dpPortNo
                        (triad.ifname, newTriad)
                }).toMap
                op.act()
                op.validate(prevInterfaceToTriad)
                entangler.clear()
            }
        } catch { case e: Throwable =>
            throw new Exception(s"Failed with history = $arrayBuffer", e)
        }
    }

    "A failed DP port create operation" should "be retried" in {
        val entangler = new DatapathPortEntangler {
            val log = Logger(NOPLogger.NOP_LOGGER)
            override protected val singleThreadExecutionContext =
                ExecutionContext.callingThread

            var shouldFail = true
            var portActive = false

            protected val driver = new DatapathStateDriver(new Datapath(0, "midonet"))

            def addToDatapath(interfaceName: String): Future[(DpPort, Int)] =
                if (shouldFail) {
                    shouldFail = false
                    Future.failed(new Exception)
                } else {
                    val dpPort = DpPort.fakeFrom(new NetDevPort(interfaceName), 1)
                    Future.successful((dpPort, 1))
                }

            def removeFromDatapath(port: DpPort): Future[Boolean] = {
                Future.successful(true)
            }

            def setVportStatus(port: DpPort, vport: UUID, tunnelKey: Long,
                                        isActive: Boolean): Unit = {
                portActive = isActive
            }
        }

        val id = UUID.randomUUID()
        val binding = new PortBinding(id, 1, "eth1")
        val desc = new InterfaceDescription("eth1")
        desc.setUp(true)
        entangler.updateVPortInterfaceBindings(Map(id -> binding))
        entangler.updateInterface(desc)

        entangler.portActive should be (false)
        entangler.interfaceToTriad containsKey "eth1" should be (true)
        entangler.vportToTriad containsKey id should be (true)
        entangler.dpPortNumToTriad containsKey 1 should be (false)
        entangler.keyToTriad containsKey 1 should be (false)

        entangler.updateInterface(desc)

        (entangler.interfaceToTriad get "eth1" dpPort) should be (
            DpPort.fakeFrom(new NetDevPort("eth1"), 1))
        entangler.dpPortNumToTriad containsKey 1 should be (true)
        entangler.keyToTriad containsKey 1L should be (true)
    }
}
