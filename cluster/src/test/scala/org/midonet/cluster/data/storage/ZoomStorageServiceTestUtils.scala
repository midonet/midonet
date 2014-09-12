/**
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.storage

import java.util.{List => JList}

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.utils.EnsurePath
import org.apache.zookeeper.KeeperException
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import rx.{Observable, Observer, Subscription}

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
trait ZoomStorageServiceTester extends StorageServiceTester {
    var curator: CuratorFramework = null
    var zoom: ZookeeperObjectMapper = null
    def zkRootDir: String
    def deviceClasses: Array[Class[_]]

    @throws(classOf[NotFoundException])
    @throws(classOf[ObjectExistsException])
    @throws(classOf[ReferenceConflictException])
    override def create(o: Object) {
        zoom.create(o)
    }

    @throws(classOf[NotFoundException])
    @throws(classOf[ReferenceConflictException])
    override def update(o: Object) {
        zoom.update(o)
    }

    @throws(classOf[NotFoundException])
    @throws(classOf[ObjectReferencedException])
    override def delete(clazz: Class[_], id: Object) {
        zoom.delete(clazz, id)
    }

    @throws(classOf[NotFoundException])
    override def get[T](clazz: Class[T], id: Object): T = {
        zoom.get(clazz, id)
    }

    override def getAll[T](clazz: Class[T]): JList[T] = {
        zoom.getAll(clazz)
    }

    override def exists(clazz: Class[_], id: Object): Boolean = {
        zoom.exists(clazz, id)
    }

    override def subscribe[T](clazz: Class[T], id: scala.Any,
                              obs: Observer[_ >: T]): Subscription =
        throw new NotImplementedError

    override def subscribeAll[T](
            clazz: Class[T], obs: Observer[_ >: Observable[T]]): Subscription =
        throw new NotImplementedError()

    override def multi(ops: java.util.List[ZoomOp]): Unit = zoom.multi(ops)

    override def isRegistered(c: Class[_]): Boolean = zoom.isRegistered(c)

    override def registerClass(c: Class[_]): Unit = zoom.registerClass(c)

    @throws[Exception]
    override def cleanUpDirectories() {
        try {
            curator.delete().deletingChildrenIfNeeded().forPath(zkRootDir)
        } catch {
            case _: KeeperException.NoNodeException =>
                // Nonde may not exist yet.
        }
    }

    @throws[Exception]
    override def cleanUpDeviceData() {
        for (device <- deviceClasses) {
            try {
                curator.delete().deletingChildrenIfNeeded()
                       .forPath(zoom.getPath(device))
            } catch {
                case _: KeeperException.NoNodeException =>
                    // Nonde may not exist yet.
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
}
