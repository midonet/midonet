/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman

import java.util.UUID
import scala.collection.JavaConversions.asJavaCollection

import akka.event.LoggingAdapter
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

import org.midonet.midolman.VirtualPortManager.Controller
import org.midonet.midolman.host.interfaces.InterfaceDescription
import org.midonet.odp._
import org.midonet.odp.ports._

@RunWith(classOf[JUnitRunner])
class VirtualPortManagerTest extends Suite with FeatureSpec with ShouldMatchers {

    import VirtualPortManager.Controller

    class MockController extends Controller {
        var vportStatus = List[(NetDevPort, UUID, Boolean)]()
        var addDp = List[String]()
        var removeDp = List[NetDevPort]()
        def setVportStatus(port: Port[_, _], vportId: UUID, isActive: Boolean) {
            vportStatus =
                (port.asInstanceOf[NetDevPort], vportId, isActive) ::vportStatus
        }
        def addToDatapath(interfaceName: String) {
            addDp = interfaceName ::addDp
        }
        def removeFromDatapath(port: Port[_, _]) {
            removeDp = port.asInstanceOf[NetDevPort] ::removeDp
        }
        def checkBlank() {
            vportStatus.isEmpty should be(true)
            addDp.isEmpty should be(true)
            removeDp.isEmpty should be(true)
        }
    }

    def getVPM() = {
        val controller = new MockController
        (new VirtualPortManager(controller), controller)
    }

    val ids = for (_ <- 1 to 3) yield UUID.randomUUID
    val itfs = List("eth0", "eth1", "eth2")
    val itfsInfo = itfs.map{ new InterfaceDescription(_) }
    val portNo = List(1,2,3)
    val dpPorts = itfs.zip(portNo).map {
        case (itf, no) => new NetDevPort(itf).setPortNo(no)
    }

    val bindings = (itfs, dpPorts zip portNo, ids zip itfsInfo).zipped.toList

    implicit val log = SoloLogger(classOf[VirtualPortManagerTest])

    feature("smoke test") {
        scenario("does nothing when created") {
            val (vpm, controller) = getVPM()
            controller.checkBlank()
        }
    }

    feature("Notifying the VPM about a successfull dpport add request") {

        val (itf, (dpport, portno), (id, _)) = bindings(0)

        def mappingCheck(vpm: VirtualPortManager) {
            vpm.dpPortsInProgress.contains(itf) should be(false)
            vpm.interfaceToDpPort.get(itf) should be(Some(dpport))
            vpm.dpPortNumToInterface.get(portno) should be(Some(itf))
        }

        scenario("no bindings registered, the DPC did not added it itself") {
            val (vpm, controller) = getVPM()
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf

            mappingCheck(vpm.datapathPortAdded(dpport))

            controller.checkBlank()
        }

        scenario("no bindings registered, the DPC added it itself") {
            val (vpm, controller) = getVPM()
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
            vpm.dpPortsWeAdded = vpm.dpPortsWeAdded + itf

            val newVpm = vpm.datapathPortAdded(dpport)

            // check mapping
            newVpm.dpPortsInProgress.contains(itf) should be(true)
            newVpm.dpPortsWeAdded.contains(itf) should be(false)
            newVpm.interfaceToDpPort.get(itf) should be(Some(dpport))
            newVpm.dpPortNumToInterface.get(portno) should be(Some(itf))

            // no binding, so the dpPort should be request to be removed
            controller.removeDp.headOption should be(Some(dpport))
            controller.addDp.headOption should be(None)
        }

        scenario("bindings present, dpport was active") {
            val (vpm, controller) = getVPM()

            // prepare the vpm state
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
            vpm.interfaceToVport = vpm.interfaceToVport + (itf, id)
            vpm.interfaceToStatus = vpm.interfaceToStatus + (itf -> true)

            mappingCheck(vpm.datapathPortAdded(dpport))

            // change of status notification
            controller.removeDp.headOption should be(None)
            controller.addDp.headOption should be(None)
            controller.vportStatus.headOption should be(Some((dpport, id, true)))
        }

        scenario("bindings present, dpport was inactive") {
            val (vpm, controller) = getVPM()

            // prepare the vpm state
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
            vpm.interfaceToVport = vpm.interfaceToVport + (itf, id)
            vpm.interfaceToStatus = vpm.interfaceToStatus + (itf -> false)

            mappingCheck(vpm.datapathPortAdded(dpport))

            controller.checkBlank()
        }
    }
}
