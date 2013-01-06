/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman

import collection.JavaConversions.asJavaCollection

import host.interfaces.InterfaceDescription
import org.easymock.EasyMock._
import org.scalatest.{BeforeAndAfter, Suite}
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.mock.EasyMockSugar
import com.midokura.midolman.VirtualPortManager.Controller
import java.util.UUID
import com.midokura.sdn.dp.ports.NetDevPort
import akka.event.LoggingAdapter
import org.slf4j.LoggerFactory

class VirtualPortManagerTest extends Suite with BeforeAndAfter
        with ShouldMatchers with EasyMockSugar {
    var vportMgr: VirtualPortManager = _
    var mockController: Controller = _
    var vport1: UUID = _
    var itf: InterfaceDescription = _
    var itfList: List[InterfaceDescription] = _
    var dpPort: NetDevPort = _

    before {
        mockController = mock[Controller]
        vportMgr = new VirtualPortManager(mockController,
            new LoggingAdapter {
                val log =
                    LoggerFactory.getLogger(classOf[VirtualPortManagerTest])

                def isErrorEnabled = true
                def isWarningEnabled = true
                def isInfoEnabled = true
                def isDebugEnabled = true

                protected def notifyError(cause: Throwable, message: String) {
                    log.error(message, cause)
                }

                protected def notifyError(message: String) {
                    log.error(message)
                }

                protected def notifyWarning(message: String) {
                    log.warn(message)
                }

                protected def notifyInfo(message: String) {
                    log.info(message)
                }

                protected def notifyDebug(message: String) {
                    log.debug(message)
                }
            }
        )
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
