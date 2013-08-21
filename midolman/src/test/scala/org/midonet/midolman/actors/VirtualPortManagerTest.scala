/*
 * Copyright 2012 Midokura Europe SARL
 */

package org.midonet.midolman

import collection.JavaConversions.asJavaCollection
import java.util.UUID

import akka.event.LoggingAdapter
import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.EasyMockSugar

import host.interfaces.InterfaceDescription
import org.easymock.EasyMock._

import org.midonet.midolman.VirtualPortManager.Controller
import org.midonet.odp.ports.NetDevPort

//@RunWith(classOf[JUnitRunner])
class VirtualPortManagerTest extends Suite
        with BeforeAndAfter with ShouldMatchers with EasyMockSugar {

    var vportMgr: VirtualPortManager = _
    var mockController: Controller = _
    var vport1: UUID = _
    var itf: InterfaceDescription = _
    var itfList: List[InterfaceDescription] = _
    var dpPort: NetDevPort = _

    implicit val log = SoloLogger(classOf[VirtualPortManagerTest])

    before {
        mockController = mock[Controller]
        vportMgr = new VirtualPortManager(mockController)
        vport1 = UUID.randomUUID()
        itf = new InterfaceDescription("eth0")
        itfList = List(itf)
        dpPort = new NetDevPort("eth0")
    }

    def testDoesNothingOnCreate() {
        replay(mockController)
        verify(mockController)
    }

    def testRequestsDpPortAdd() {
        replay(mockController)

        vportMgr.updateInterfaces(itfList)
        verify(mockController)

        reset(mockController)
        mockController.addToDatapath("eth0")
        replay(mockController)

        vportMgr.updateVPortInterfaceBindings(Map(vport1 -> "eth0"))
        verify(mockController)
    }

    def testReportsVportActiveOnInterfaceUp() {
        testRequestsDpPortAdd()

        // Adding port when itf is DOWN should not result in any calls to
        // Controller.
        vportMgr.datapathPortAdded(dpPort)

        // The controller is notified when the interface status goes UP.
        reset(mockController)
        mockController.setVportStatus(dpPort, vport1, true)
        replay(mockController)

        itf.setHasLink(true)
        itf.setUp(true)
        vportMgr.updateInterfaces(itfList)
        verify(mockController)
    }

    def testReportsVportActiveOnDpPortAdded() {
        testRequestsDpPortAdd()

        // Set the itf to UP. The datapath hasn't been added,
        // so the vport isn't UP yet.
        itf.setHasLink(true)
        itf.setUp(true)
        vportMgr.updateInterfaces(itfList)

        // Now when the vportMgr learns that the dp port has been added
        // it notifies that the vport is UP.
        reset(mockController)
        mockController.setVportStatus(dpPort, vport1, true)
        replay(mockController)
        vportMgr.datapathPortAdded(dpPort)
        verify(mockController)
    }

    def testReportsVportInactiveOnStatusDown() {
        testReportsVportActiveOnInterfaceUp()

        reset(mockController)
        mockController.setVportStatus(dpPort, vport1, false)
        replay(mockController)

        itf.setUp(false)
        vportMgr.updateInterfaces(itfList)
        verify(mockController)
    }

    def testUnexpectedDpPortRemoval() {
        testReportsVportActiveOnInterfaceUp()

        // If the dpPort is suddenly removed, the vportMgr should declare the
        // vportId to be inactive. Since the binding still exists,
        // it should also request that the dpPort be re-added.
        reset(mockController)
        mockController.setVportStatus(dpPort, vport1, false)
        mockController.addToDatapath("eth0")
        replay(mockController)
        vportMgr.datapathPortRemoved("eth0")
        verify(mockController)
    }

    def testRequestsDpPortRemoval() {
        testReportsVportActiveOnInterfaceUp()

        reset(mockController)
        mockController.removeFromDatapath(dpPort)
        mockController.setVportStatus(dpPort, vport1, false)
        replay(mockController)

        vportMgr.updateVPortInterfaceBindings(Map())
    }
}
