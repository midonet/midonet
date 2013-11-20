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
    val portNo = List[java.lang.Integer](1,2,3)
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

    feature("Notifying the VPM about a successfull dpport remove request") {

        val (itf, (dpport, portno), (id, _)) = bindings(0)

        def addMapping(vpm: VirtualPortManager) {
            vpm.interfaceToDpPort = vpm.interfaceToDpPort + (itf -> dpport)
            vpm.dpPortNumToInterface = vpm.dpPortNumToInterface + (portno -> itf)
        }

        def addBinding(vpm: VirtualPortManager) {
            vpm.interfaceToVport = vpm.interfaceToVport + (itf, id)
        }

        def addStatus(vpm: VirtualPortManager, status: Boolean) {
            vpm.interfaceToStatus = vpm.interfaceToStatus + (itf -> status)
        }

        def checkNoMapping(vpm: VirtualPortManager) {
            vpm.interfaceToDpPort.get(itf) should be (None)
            vpm.dpPortNumToInterface.get(portno) should be (None)
        }

        scenario("did not have mapping") {
            val (vpm, controller) = getVPM()
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf

            val newVpm = vpm.datapathPortRemoved(itf)
            newVpm.dpPortsInProgress.contains(itf) should be(false)

            controller.checkBlank()
        }

        scenario("has mapping, does not have binding") {
            val (vpm, controller) = getVPM()
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
            addMapping(vpm)

            val newVpm = vpm.datapathPortRemoved(itf)
            newVpm.dpPortsInProgress.contains(itf) should be(false)
            checkNoMapping(newVpm)

            controller.checkBlank()
        }

        scenario("has mapping, has binding, unknown status") {
            val (vpm, controller) = getVPM()
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
            addMapping(vpm)
            addBinding(vpm)

            val newVpm = vpm.datapathPortRemoved(itf)
            newVpm.dpPortsInProgress.contains(itf) should be(false)
            checkNoMapping(newVpm)

            controller.checkBlank()
        }

        scenario("has mapping, has binding, status was inactive") {
            val (vpm, controller) = getVPM()
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
            addMapping(vpm)
            addBinding(vpm)
            addStatus(vpm, false)

            val newVpm = vpm.datapathPortRemoved(itf)
            newVpm.dpPortsInProgress.contains(itf) should be(true)
            newVpm.dpPortsWeAdded.contains(itf) should be(true)
            checkNoMapping(newVpm)

            controller.addDp.headOption should be(Some(itf))
            controller.vportStatus.headOption should be(None)
        }

        scenario("has mapping, has binding, status was active, own request") {
            val (vpm, controller) = getVPM()
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
            addMapping(vpm)
            addBinding(vpm)
            addStatus(vpm, true)

            val newVpm = vpm.datapathPortRemoved(itf)
            newVpm.dpPortsInProgress.contains(itf) should be(true)
            newVpm.dpPortsWeAdded.contains(itf) should be(true)
            checkNoMapping(newVpm)

            controller.addDp.headOption should be(Some(itf))
            controller.vportStatus.headOption should be(None)
        }

        scenario("has mapping, has binding, status was active, not own request") {
            val (vpm, controller) = getVPM()
            addMapping(vpm)
            addBinding(vpm)
            addStatus(vpm, true)

            val newVpm = vpm.datapathPortRemoved(itf)
            newVpm.dpPortsInProgress.contains(itf) should be(true)
            newVpm.dpPortsWeAdded.contains(itf) should be(true)
            checkNoMapping(newVpm)

            controller.addDp.headOption should be(Some(itf))
            controller.vportStatus.headOption should be(Some(dpport, id, false))
        }

    }

    feature("Notifying the VPM about a dpport to forget about") {
        scenario("The request was registered as in progress") {
            val (itf, (dpport, portno), (id, _)) = bindings(0)
            val (vpm, controller) = getVPM()

            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
            vpm.dpPortsWeAdded = vpm.dpPortsWeAdded + itf
            vpm.interfaceToStatus = vpm.interfaceToStatus + (itf -> true)

            val newVpm = vpm.datapathPortForget(dpport)
            newVpm.dpPortsInProgress.contains(itf) should be(false)
            newVpm.dpPortsWeAdded.contains(itf) should be(false)
            newVpm.interfaceToStatus.get(itf) should be(None)
        }
    }

    feature("Notifying the VPM about a new vport binding") {

        val (itf, (dpport, portno), (id, _)) = bindings(0)

        def addStatus(vpm: VirtualPortManager, status: Boolean) {
            vpm.interfaceToStatus = vpm.interfaceToStatus + (itf -> status)
        }

        def addMapping(vpm: VirtualPortManager) {
            vpm.interfaceToDpPort = vpm.interfaceToDpPort + (itf -> dpport)
            vpm.dpPortNumToInterface = vpm.dpPortNumToInterface + (portno -> itf)
        }

        scenario("binding added, no interface status") {
            val (vpm, controller) = getVPM()

            val newVpm = vpm.updateVPortInterfaceBindings(Map(id -> itf))
            newVpm.interfaceToVport.get(itf) should be(Some(id))

            controller.checkBlank()
        }

        scenario("binding added, interface status present, no dpport") {
            val (vpm, controller) = getVPM()
            addStatus(vpm, true)

            val newVpm = vpm.updateVPortInterfaceBindings(Map(id -> itf))
            newVpm.interfaceToVport.get(itf) should be(Some(id))
            newVpm.dpPortsWeAdded.contains(itf) should be(true)
            newVpm.dpPortsInProgress.contains(itf) should be(true)

            controller.addDp.headOption should be(Some(itf))
            controller.vportStatus.headOption should be(None)
        }

        scenario("binding added, interface status present, dpport present") {
            val (vpm, controller) = getVPM()
            addStatus(vpm, true)
            addMapping(vpm)

            val newVpm = vpm.updateVPortInterfaceBindings(Map(id -> itf))
            newVpm.interfaceToVport.get(itf) should be(Some(id))
            newVpm.dpPortsWeAdded.contains(itf) should be(false)
            newVpm.dpPortsInProgress.contains(itf) should be(false)

            controller.addDp.headOption should be(None)
            controller.vportStatus.headOption should be(Some((dpport, id, true)))
        }
    }

    feature("Notifying the VPM about a vport binding removal") {

        val (itf, (dpport, portno), (id, _)) = bindings(0)

        def addStatus(vpm: VirtualPortManager, status: Boolean) {
            vpm.interfaceToStatus = vpm.interfaceToStatus + (itf -> status)
        }

        def addMapping(vpm: VirtualPortManager) {
            vpm.interfaceToDpPort = vpm.interfaceToDpPort + (itf -> dpport)
            vpm.dpPortNumToInterface = vpm.dpPortNumToInterface + (portno -> itf)
        }

        def addBinding(vpm: VirtualPortManager) {
            vpm.interfaceToVport = vpm.interfaceToVport + (itf, id)
        }

        scenario("noops") {
            val (vpm, controller) = getVPM()

            val newVpm = vpm.updateVPortInterfaceBindings(Map())
            newVpm.interfaceToVport.get(itf) should be(None)

            controller.checkBlank()
        }

        scenario("binding removed, no mapping, was not in progress") {
            val (vpm, controller) = getVPM()
            addBinding(vpm)

            val newVpm = vpm.updateVPortInterfaceBindings(Map())
            newVpm.interfaceToVport.get(itf) should be(None)
            newVpm.dpPortsInProgress.contains(itf) should be(false)

            controller.checkBlank()
        }

        scenario("binding removed, no mapping, was in progress") {
            val (vpm, controller) = getVPM()
            addBinding(vpm)
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf

            val newVpm = vpm.updateVPortInterfaceBindings(Map())
            newVpm.interfaceToVport.get(itf) should be(None)
            newVpm.dpPortsInProgress.contains(itf) should be(false)

            controller.checkBlank()
        }

        scenario("binding removed, mapping, status inactive") {
            val (vpm, controller) = getVPM()
            addBinding(vpm)
            addMapping(vpm)
            vpm.dpPortsWeAdded = vpm.dpPortsWeAdded + itf

            val newVpm = vpm.updateVPortInterfaceBindings(Map())
            newVpm.interfaceToVport.get(itf) should be(None)
            newVpm.dpPortsInProgress.contains(itf) should be(true)
            newVpm.dpPortsWeAdded.contains(itf) should be(false)

            controller.removeDp.headOption should be(Some(dpport))
            controller.vportStatus.headOption should be(None)
        }

        scenario("binding removed, mapping, status active, dpc request") {
            val (vpm, controller) = getVPM()
            addBinding(vpm)
            addMapping(vpm)
            addStatus(vpm, true)
            vpm.dpPortsWeAdded = vpm.dpPortsWeAdded + itf

            val newVpm = vpm.updateVPortInterfaceBindings(Map())
            newVpm.interfaceToVport.get(itf) should be(None)
            newVpm.dpPortsInProgress.contains(itf) should be(true)
            newVpm.dpPortsWeAdded.contains(itf) should be(false)

            controller.removeDp.headOption should be(Some(dpport))
            controller.vportStatus.headOption should be(Some((dpport, id, false)))
        }

        scenario("binding removed, mapping, status active, request in progress") {
            val (vpm, controller) = getVPM()
            addBinding(vpm)
            addMapping(vpm)
            addStatus(vpm, true)
            vpm.dpPortsWeAdded = vpm.dpPortsWeAdded + itf
            vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf

            val newVpm = vpm.updateVPortInterfaceBindings(Map())
            newVpm.interfaceToVport.get(itf) should be(None)
            newVpm.dpPortsInProgress.contains(itf) should be(true)
            newVpm.dpPortsWeAdded.contains(itf) should be(true)

            controller.removeDp.headOption should be(None)
            controller.vportStatus.headOption should be(Some((dpport, id, false)))
        }
    }
}
