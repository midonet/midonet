/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.data.storage

import scala.concurrent.Future

import org.apache.zookeeper.KeeperException
import org.scalatest.Suite

import rx.Observable

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.{Obj, ObjId}
import org.midonet.cluster.models.Topology.{Chain, Network, Port, Router, Rule}
import org.midonet.cluster.util.CuratorTestFramework

/**
 * DTO for ZOOM binding.
 */
class ZoomBinding(val leftClass: Class[_],
                  val leftField: String,
                  val onLeftDelete: DeleteAction,
                  val rightClass: Class[_],
                  val rightField: String,
                  val onRightDelete: DeleteAction) {}


/**
 * A trait implementing the common API for testing ZOOM-based Storage Service.
 */
trait ZoomStorageTester extends StorageTester
                                with CuratorTestFramework { this: Suite =>
    var zoom: ZookeeperObjectMapper = null
    val deviceClasses: Array[Class[_]] =
        Array(classOf[Network], classOf[Chain], classOf[Port], classOf[Router],
              classOf[Rule])

    val bindings = Array(
            new ZoomBinding(
                    classOf[Network], "inbound_filter_id", DeleteAction.CLEAR,
                    classOf[Chain], "network_inbound_ids", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Network], "outbound_filter_id", DeleteAction.CLEAR,
                    classOf[Chain], "network_outbound_ids", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Router], "port_ids", DeleteAction.CASCADE,
                    classOf[Port], "router_id", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Network], "port_ids", DeleteAction.CASCADE,
                    classOf[Port], "network_id", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Port], "peer_id", DeleteAction.CLEAR,
                    classOf[Port], "peer_id", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Chain], "rule_ids", DeleteAction.CASCADE,
                    classOf[Rule], "chain_id", DeleteAction.CLEAR)
            )

    @throws(classOf[NotFoundException])
    @throws(classOf[ObjectExistsException])
    @throws(classOf[ReferenceConflictException])
    override def create(o: Obj) {
        zoom.create(o)
    }

    @throws(classOf[NotFoundException])
    @throws(classOf[ReferenceConflictException])
    override def update(o: Obj) {
        zoom.update(o)
    }

    @throws(classOf[NotFoundException])
    @throws(classOf[ReferenceConflictException])
    override def update[T <: Obj](o: T, validator: UpdateValidator[T] = null) {
        zoom.update(o, validator)
    }

    @throws(classOf[NotFoundException])
    @throws(classOf[ObjectReferencedException])
    override def delete(clazz: Class[_], id: ObjId) {
        zoom.delete(clazz, id)
    }

    @throws(classOf[ObjectReferencedException])
    override def deleteIfExists(clazz: Class[_], id: ObjId) {
        zoom.deleteIfExists(clazz, id)
    }

    @throws(classOf[NotFoundException])
    override def get[T](clazz: Class[T], id: ObjId): Future[T] = {
        zoom.get(clazz, id)
    }

    override def getAll[T](clazz: Class[T],
                           ids: Seq[_ <: ObjId]): Future[Seq[T]] = {
        zoom.getAll(clazz, ids)
    }

    override def getAll[T](clazz: Class[T]): Future[Seq[T]] = {
        zoom.getAll(clazz)
    }

    override def exists(clazz: Class[_], id: ObjId): Future[Boolean] = {
        zoom.exists(clazz, id)
    }

    /**
     * Executes multiple create, update, and/or delete operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    override def multi(ops: Seq[PersistenceOp]) {
        zoom.multi(ops)
    }

    override def observable[T](clazz: Class[T], id: ObjId): Observable[T] = {
        zoom.observable(clazz, id)
    }

    override def observable[T](clazz: Class[T]): Observable[Observable[T]] = {
        zoom.observable(clazz)
    }

    override def isRegistered(c: Class[_]): Boolean = {
        zoom.isRegistered(c)
    }

    override def registerClass(c: Class[_]): Unit = zoom.registerClass(c)

    override protected def setup(): Unit = {
        zoom = new ZookeeperObjectMapper(config, "zoom", "host", curator)
        registerClasses(deviceClasses, bindings)
        zoom.build()
    }

    override def cleanUpDirectories(): Unit = {
        clearZookeeper()
    }

    @throws[Exception]
    override def cleanUpDeviceData() {
        for (device <- deviceClasses) {
            try {
                curator.delete().deletingChildrenIfNeeded()
                       .forPath(zoom.classPath(device))
            } catch {
                case _: KeeperException.NoNodeException =>
                    // Node may not exist yet.
            }

            curator.createContainers(zoom.classPath(device))
        }
    }

    def registerClasses(deviceClasses: Array[Class[_]],
                        bindings: Array[ZoomBinding]) {
        for (clazz <- deviceClasses) {
            zoom.registerClass(clazz)
        }

        for (binding <- bindings) {
            zoom.declareBinding(
                binding.leftClass, binding.leftField, binding.onLeftDelete,
                binding.rightClass, binding.rightField, binding.onRightDelete)
        }
    }

    override def declareBinding(leftClass: Class[_], leftFieldName: String,
                                onDeleteLeft: DeleteAction,
                                rightClass: Class[_], rightFieldName: String,
                                onDeleteRight: DeleteAction): Unit = {
        zoom.declareBinding(leftClass, leftFieldName, onDeleteLeft,
                            rightClass, rightFieldName, onDeleteRight)
    }

    override def build() = zoom.build()
    override def isBuilt: Boolean = zoom.isBuilt

}
