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
class VirtualPortManagerTest extends FeatureSpec with Matchers {

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

  val (itf, (dpport, portno), (id, itfInfo)) = bindings(0)

  def addStatus(vpm: VirtualPortManager, status: Boolean) {
    vpm.interfaceToStatus = vpm.interfaceToStatus + (itf -> status)
  }

  def itfScan(status: Boolean = true) = {
    val itfInfoList = List(itfInfo)
    itfInfoList.foreach{ _.setUp(status) }
    itfInfoList.foreach{ _.setHasLink(status) }
    itfInfoList
  }

  def addMapping(vpm: VirtualPortManager) {
    vpm.interfaceToDpPort = vpm.interfaceToDpPort + (itf -> dpport)
    vpm.dpPortNumToInterface = vpm.dpPortNumToInterface + (portno -> itf)
  }

  def addBinding(vpm: VirtualPortManager) {
    vpm.interfaceToVport = vpm.interfaceToVport + (itf, id)
  }

  def checkNoMapping(vpm: VirtualPortManager) {
    vpm.interfaceToDpPort.get(itf) should be (None)
    vpm.dpPortNumToInterface.get(portno) should be (None)
  }

  def checkMapping(vpm: VirtualPortManager) {
    vpm.interfaceToDpPort.get(itf) should be(Some(dpport))
    vpm.dpPortNumToInterface.get(portno) should be(Some(itf))
  }

  def checkTracking(
      vpm: VirtualPortManager, progress: Boolean, weAdded: Boolean) {
    vpm.dpPortsInProgress.contains(itf) should be(progress)
    vpm.dpPortsWeAdded.contains(itf) should be(weAdded)
  }

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

      val newVpm = vpm.datapathPortAdded(dpport)
      checkMapping(newVpm)
      newVpm.dpPortsInProgress.contains(itf) should be(false)

      controller.checkBlank()
    }

    scenario("no bindings registered, the DPC added it itself") {
      val (vpm, controller) = getVPM()
      vpm.dpPortsInProgress = vpm.dpPortsInProgress + itf
      vpm.dpPortsWeAdded = vpm.dpPortsWeAdded + itf

      val newVpm = vpm.datapathPortAdded(dpport)

      // check mapping
      checkTracking(newVpm, true, false)
      checkMapping(newVpm)

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

      val newVpm = vpm.datapathPortAdded(dpport)
      newVpm.dpPortsInProgress.contains(itf) should be(false)
      checkMapping(newVpm)

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
      checkTracking(newVpm, true, true)
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
      checkTracking(newVpm, true, true)
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
      checkTracking(newVpm, true, true)
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
      checkTracking(newVpm, true, true)

      controller.addDp.headOption should be(Some(itf))
      controller.vportStatus.headOption should be(None)
    }

    scenario("binding added, interface status present, dpport present") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)
      addMapping(vpm)

      val newVpm = vpm.updateVPortInterfaceBindings(Map(id -> itf))
      newVpm.interfaceToVport.get(itf) should be(Some(id))
      checkTracking(newVpm, false, false)

      controller.addDp.headOption should be(None)
      controller.vportStatus.headOption should be(Some((dpport, id, true)))
    }
  }

  feature("Notifying the VPM about a vport binding removal") {

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
      checkTracking(newVpm, true, false)

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
      checkTracking(newVpm, true, false)

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
      checkTracking(newVpm, true, true)

      controller.removeDp.headOption should be(None)
      controller.vportStatus.headOption should be(Some((dpport, id, false)))
    }
  }

  feature("Notifying the VPM about new interface") {

    scenario("unknown interface, no binding (no op)") {
      val (vpm, controller) = getVPM()

      val newVpm = vpm.updateInterfaces(List(itfInfo))

      controller.checkBlank()
      newVpm.interfaceToStatus.get(itf) should be(Some(false))
    }

    scenario("unknown interface, has binding, no mapping yet") {
      val (vpm, controller) = getVPM()
      addBinding(vpm)

      val newVpm = vpm.updateInterfaces(itfScan())

      checkTracking(newVpm, true, true)
      controller.addDp.headOption should be(Some(itf))
      controller.vportStatus.headOption should be(None)
    }

    scenario("unknown interface, status up, has binding, has dpport mapping") {
      val (vpm, controller) = getVPM()
      addBinding(vpm)
      addMapping(vpm)

      val newVpm = vpm.updateInterfaces(itfScan(true))

      controller.addDp.headOption should be(None)
      controller.vportStatus.headOption should be(Some((dpport,id,true)))
    }

    scenario("unknown interface, status down, has binding, has dpport mapping") {
      val (vpm, controller) = getVPM()
      addBinding(vpm)
      addMapping(vpm)

      val newVpm = vpm.updateInterfaces(itfScan(false))

      controller.addDp.headOption should be(None)
      controller.vportStatus.headOption should be(None)
    }

    scenario("known interface, status up, not unknown or datapath type, has mapping") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)
      addMapping(vpm)

      val itfInfoList = itfScan(true)
      itfInfoList.foreach{ _.setEndpoint(InterfaceDescription.Endpoint.TUNTAP) }

      val newVpm = vpm.updateInterfaces(itfInfoList)

      checkTracking(newVpm, true, true)
      controller.addDp.headOption should be(Some(itf))
    }

    scenario("known interface, new and previous status identitcal") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, false)

      vpm.updateInterfaces(itfScan(false))
        .interfaceToStatus.get(itf) should be(Some(false))
      controller.checkBlank()

      val (vpm2, controller2) = getVPM()
      addStatus(vpm2, true)

      vpm.updateInterfaces(itfScan(true))
        .interfaceToStatus.get(itf) should be(Some(true))
      controller2.checkBlank()
    }

    scenario("known interface, new and previous status different, no binding") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)

      val newVpm = vpm.updateInterfaces(itfScan(false))

      newVpm.interfaceToStatus.get(itf) should be(Some(false))
      controller.checkBlank()
    }

    scenario("known interface, new and previous status different, has binding, no mapping") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)
      addBinding(vpm)

      val newVpm = vpm.updateInterfaces(itfScan(false))

      newVpm.interfaceToStatus.get(itf) should be(Some(false))
      controller.checkBlank()
    }

    scenario("known interface, new and previous status different, has binding and mapping") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)
      addBinding(vpm)
      addMapping(vpm)

      val newVpm = vpm.updateInterfaces(itfScan(false))

      newVpm.interfaceToStatus.get(itf) should be(Some(false))
      controller.vportStatus.headOption should be(Some((dpport, id, false)))
    }
  }

  feature("Notifying the VPM about an interface removal") {

    scenario("no ops") {
      val (vpm, controller) = getVPM()

      val newVpm = vpm.updateInterfaces(Nil)

      controller.checkBlank()
    }

    scenario("no binding, no mapping") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)

      val newVpm = vpm.updateInterfaces(Nil)
      newVpm.interfaceToStatus.get(itf) should be(None)

      controller.checkBlank()
    }

    scenario("no binding, has mapping") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)
      addMapping(vpm)

      val newVpm = vpm.updateInterfaces(Nil)

      checkTracking(vpm, false, false)
    }

    scenario("has binding, no mapping") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)
      addBinding(vpm)

      val newVpm = vpm.updateInterfaces(Nil)

      controller.vportStatus.headOption should be(None)
    }

    scenario("has binding, has mapping") {
      val (vpm, controller) = getVPM()
      addStatus(vpm, true)
      addMapping(vpm)
      addBinding(vpm)

      val newVpm = vpm.updateInterfaces(Nil)

      controller.vportStatus.headOption should be(Some((dpport,id,false)))
      checkTracking(vpm, false, false)
    }
  }
}
