/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import java.util.{List => JList}

import org.apache.curator.utils.EnsurePath
import org.apache.zookeeper.KeeperException
import org.scalatest.Suite

import rx.{Observable, Observer, Subscription}

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.models.Devices.{Bridge, Chain, Port, Router, Rule}
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
trait ZoomStorageServiceTester extends StorageServiceTester
                               with CuratorTestFramework { this: Suite =>
    var zoom: ZookeeperObjectMapper = null
    val deviceClasses: Array[Class[_]] =
        Array(classOf[Bridge], classOf[Chain], classOf[Port], classOf[Router],
              classOf[Rule])

    val bindings = Array(
            new ZoomBinding(
                    classOf[Bridge], "inbound_filter_id", DeleteAction.CLEAR,
                    classOf[Chain], "bridge_ids", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Bridge], "outbound_filter_id", DeleteAction.CLEAR,
                    classOf[Chain], "bridge_ids", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Router], "port_ids", DeleteAction.CASCADE,
                    classOf[Port], "router_id", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Bridge], "port_ids", DeleteAction.CASCADE,
                    classOf[Port], "bridge_id", DeleteAction.CLEAR),
            new ZoomBinding(
                    classOf[Port], "peer_uuid", DeleteAction.CLEAR,
                    classOf[Port], "peer_uuid", DeleteAction.CLEAR),
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
    override def update[T <: Obj](o: T, validator: UpdateValidator[T]) {
        zoom.update(o)
    }

    @throws(classOf[NotFoundException])
    @throws(classOf[ObjectReferencedException])
    override def delete(clazz: Class[_], id: ObjId) {
        zoom.delete(clazz, id)
    }

    @throws(classOf[NotFoundException])
    override def get[T](clazz: Class[T], id: ObjId): T = {
        zoom.get(clazz, id)
    }

    override def getAll[T](clazz: Class[T]): JList[T] = {
        zoom.getAll(clazz)
    }

    override def exists(clazz: Class[_], id: ObjId): Boolean = {
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

    /**
     * Executes multiple create, update, and/or delete operations atomically.
     */
    @throws[NotFoundException]
    @throws[ObjectExistsException]
    @throws[ObjectReferencedException]
    @throws[ReferenceConflictException]
    override def multi(ops: JList[PersistenceOp]) {
        zoom.multi(ops)
    }

    override def subscribe[T](clazz: Class[T],
                              id: ObjId,
                              obs: Observer[_ >: T]): Subscription = {
        zoom.subscribe[T](clazz, id, obs)
    }

    /**
     * Subscribes to the specified class. Upon subscription at time t0,
     * obs.onNext() will receive an Observable[T] for each object of class
     * T existing at time t0, and future updates at tn > t0 will each trigger
     * a call to onNext() with an Observable[T] for a new object.
     *
     * Neither obs.onCompleted() nor obs.onError() will be invoked under normal
     * circumstances.
     *
     * The subscribe() method of each of these Observables has the same behavior
     * as ZookeeperObjectMapper.subscribe(Class[T], ObjId).
     */
    override def subscribeAll[T](clazz: Class[T],
                                 obs: Observer[_ >: Observable[T]])
                                 : Subscription = {
        zoom.subscribeAll(clazz, obs)
    }

    override def isRegistered(c: Class[_]): Boolean = zoom.isRegistered(c)

    override def registerClass(c: Class[_]): Unit = zoom.registerClass(c)

    override protected def setup(): Unit = {
        zoom = new ZookeeperObjectMapper(ZK_ROOT, curator)
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
                       .forPath(zoom.getPath(device))
            } catch {
                case _: KeeperException.NoNodeException =>
                    // Node may not exist yet.
            }

            val ensurePath = new EnsurePath(zoom.getPath(device))
            ensurePath.ensure(curator.getZookeeperClient)
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
