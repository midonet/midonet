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

import java.util.ConcurrentModificationException

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import com.google.common.collect.Multimap
import com.google.protobuf.Message

import rx.Observable

import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.TransactionManager.{BindingsMap, ClassesMap}
import org.midonet.util.collection.PathMap

object TransactionManager {

    type ClassesMap = Map[Class[_], ClassInfo]
    type BindingsMap = Multimap[Class[_], FieldBinding]

    case class Key(clazz: Class[_], id: String)

    case class ObjSnapshot(obj: Obj, version: Int)

    sealed trait TxOp
    sealed trait TxNodeOp extends TxOp
    case class TxCreate(obj: Obj) extends TxOp
    case class TxUpdate(obj: Obj, version: Int) extends TxOp
    case class TxDelete(version: Int) extends TxOp
    case class TxCreateNode(value: String = null) extends TxNodeOp
    case class TxUpdateNode(value: String = null) extends TxNodeOp
    case object TxDeleteNode extends TxNodeOp

    // No-op. Used in nodeOps map to indicate that we've checked the data store
    // and confirmed that this node exists.
    case object TxNodeExists extends TxNodeOp

    private final val NewObjectVersion = -1

    @inline
    def getIdString(clazz: Class[_], id: ObjId): String = {
        if (classOf[Message].isAssignableFrom(clazz)) {
            ProtoFieldBinding.getIdString(id)
        } else {
            id.toString
        }
    }

    @inline
    private[storage] def getKey(clazz: Class[_], id: ObjId): Key = {
        Key(clazz, getIdString(clazz, id))
    }
}

/**
 * Provides basic support for a storage transaction. The manager consolidates
 * an atomic set of operations.
 * @param classes The map of registered classes.
 * @param bindings The map of registered bindings.
 */
abstract class TransactionManager(classes: ClassesMap, bindings: BindingsMap)
    extends Transaction {

    import TransactionManager._

    // This is a transaction-local cache of the objects to modify. This cache
    // is only used during the lifetime of the transaction, and all
    // modifications are applied to the cached copies, until the commit. If,
    // because of a concurrent modification, any of the cached objects become
    // out-of-date, the transaction will fail.
    private val objCache = new mutable.HashMap[Key, Option[ObjSnapshot]]()
    // This is an ordered map of all operations to be applied to ZooKeeper by
    // this transaction. Each operation specified by the user will update the
    // list of operations in this list, such that there will only be a single
    // per-object operation committed to ZooKeeper. Therefore, for any user
    // operation, the transaction will create, update or delete a ZK operation
    // in this ordered map. The changes apply as follows:
    //
    // Create:
    //   None + [create] -> TxCreate
    //   Some(TxDelete) + [create] -> TxUpdate
    //   _ + [create] -> throws ObjectExistsException
    //
    // Update:
    //   None + [update] -> TxUpdate
    //   Some(TxCreate) + [update] -> TxCreate
    //   Some(TxUpdate) + [update] -> TxUpdate
    //   _ + [update] -> NotFoundException
    //
    // Delete:
    //   None + [delete] -> if(no owners) TxDelete else TxUpdate
    //   Some(TxCreate) + [delete] -> if(no owners) None else TxCreate
    //   Some(TxUpdate) + [delete] -> if(no owners) TxDelete else TxUpdate
    //   _ + [delete] -> do nothing (idempotent delete)
    //
    // When updating an owner, the transaction deletes and recreates the owner-
    // ship node, to ensure that it corresponds to the current client session.
    // This requires  adding a TxDeleteOwner if the owner is already found in
    // the list of owners, followed by a TxCreateOwner. These are added to the
    // ownership operations for each object operation.
    protected val ops = new mutable.LinkedHashMap[Key, TxOp]

    // Transaction-local cache of node operations, indexed by node path. Node
    // operations are independent of object operations, and are used to create
    // and modify hierarchical node structures that don't fit into
    // Storage/Zoom's flat object model. As with the other caches, this lasts
    // for the life of the transaction, accumulating and flattening changes to
    // nodes.
    //
    // For example, CreateNodeOp("/a/b/c", "val1") will set nodeOps("/a/b/c") to
    // TxCreateNode("val1"), and if the operation UpdateNodeOp("/a/b/c", "val2")
    // appears later in the transaction, then TxCreateNode("val1") will be
    // replaced with TxCreateNode("val2"), as this is logically equivalent to
    // creating the node with value "val1" and then updating its value to
    // "val2".
    protected val nodeOps = new PathMap[TxNodeOp]
    nodeOps("/") = TxNodeExists // The root node always exists.

    protected def isRegistered(clazz: Class[_]): Boolean

    protected def getSnapshot(clazz: Class[_], id: ObjId): Observable[ObjSnapshot]

    protected def getIds(clazz: Class[_]): Observable[Seq[ObjId]]

    /**
     * Gets the specified object from the internal cache. If not found,
     * loads it from Zookeeper and caches it.
     *
     * @return None if the object has been marked for deletion.
     */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    private def cachedGet(clazz: Class[_], id: ObjId): Option[ObjSnapshot] = {
        objCache.getOrElseUpdate(getKey(clazz, id),
                                 Some(getSnapshot(clazz, id).toBlocking.first()))
    }

    private def getObjectId(obj: Obj) = classes(obj.getClass).idOf(obj)

    private def isDeleted(key: Key): Boolean = ops.get(key) match {
        case Some(TxDelete(_)) => true
        case _ => false
    }

    /**
     * Adds a backreference from the instance of thatClass whose ID is
     * thatId (thatObj) to thisId, using field thatField. Adds an
     * updated thatObj with back references added to the cache.
     *
     * If thatField is null, thatObj will be loaded and cached, but no
     * backreference will be added.
     */
    private def addBackreference(bdg: FieldBinding,
                                 thisId: ObjId, thatId: ObjId) {
        cachedGet(bdg.getReferencedClass, thatId).foreach { snapshot =>
            val updatedThatObj =
                bdg.addBackReference(snapshot.obj, thatId, thisId)
            updateCacheAndOp(bdg.getReferencedClass, thatId,
                             updatedThatObj.asInstanceOf[Obj])
        }
    }

    /**
     * Removes a backreference from the instance of thatClass whose ID
     * is thatId (thatObj) to thisId, using field thatField. Adds an
     * updated thatObj with back references removed to the cache.
     *
     * ThatObj is assumed to exist.
     */
    private def clearBackreference(bdg: FieldBinding, thisId: ObjId,
                                   thatId: ObjId) {
        cachedGet(bdg.getReferencedClass, thatId).foreach { snapshot =>
            val updatedThatObj = bdg.clearBackReference(snapshot.obj, thisId)
            updateCacheAndOp(bdg.getReferencedClass, thatId,
                             updatedThatObj.asInstanceOf[Obj])
        }
    }

    /** Gets the specified object within the context of the current transaction.
      * The object is either guaranteed to not be modified until the transaction
      * is committed, or the transaction will fail with a
      * [[ConcurrentModificationException]] */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    override def get[T](clazz: Class[T], id: ObjId): T = {
        cachedGet(clazz, id)
            .getOrElse(throw new NotFoundException(clazz, id))
            .obj.asInstanceOf[T]
    }

    /** Gets all objects of the specified class within the context of the
      * current transaction. The objects are either guaranteed to not be
      * modified until the transaction is committed, or the transaction will
      * fail with a [[ConcurrentModificationException]]. */
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    def getAll[T](clazz: Class[T]): Seq[T] = {
        getAll(clazz, getIds(clazz).toBlocking.first())
    }

    /** Gets the specified objects within the context of the current transaction.
      * The objects are either guaranteed to not be modified until the
      * transaction is committed, or the transaction will fail with a
      * [[ConcurrentModificationException]]. */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    def getAll[T](clazz: Class[T], ids: Seq[ObjId]): Seq[T] = {
        for (id <- ids) yield get(clazz, id)
    }

    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    @throws[ObjectExistsException]
    override def create(obj: Obj): Unit = {
        assert(isRegistered(obj.getClass))
        // Allow a creation of an object with an exclusive ownership type, whose
        // ownership may later be claimed by others with an Update operation.

        val thisId = getObjectId(obj)
        val key = getKey(obj.getClass, thisId)

        if(objCache.contains(key) && !isDeleted(key)) {
            throw new ObjectExistsException(key.clazz, key.id)
        }

        objCache(key) = ops.get(key) match {
            case None =>
                // No previous op: add a TxCreate
                ops += key -> TxCreate(obj)
                Some(ObjSnapshot(obj, NewObjectVersion))
            case Some(TxDelete(ver)) =>
                // Previous delete: add a TxUpdate with new object
                ops(key) = TxUpdate(obj, ver)
                Some(ObjSnapshot(obj, ver))
            case Some(_) =>
                throw new ObjectExistsException(key.clazz, key.id)
        }

        for (binding <- bindings.get(obj.getClass).asScala;
             thatId <- binding.getFwdReferenceAsList(obj).asScala) {
            addBackreference(binding, thisId, thatId)
        }
    }

    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    @throws[IllegalArgumentException]
    override def update(obj: Obj, validator: UpdateValidator[Obj]): Unit = {

        val clazz = obj.getClass
        assert(isRegistered(clazz), s"Class is not registered: " + clazz)

        val thisId = getObjectId(obj)
        val snapshot = cachedGet(clazz, thisId).getOrElse(
            throw new NotFoundException(clazz, thisId))

        // Invoke the validator/update callback if provided. If it returns
        // a modified object, use that in place of obj for the update.
        val newThisObj = if (validator == null) obj else {
            val modified = validator.validate(snapshot.obj, obj)
            val thisObj = if (modified != null) modified else obj
            if (getObjectId(thisObj) != thisId) {
                throw new IllegalArgumentException(
                    "Modifying the object identifier in the validator is not " +
                    "supported.")
            }
            thisObj
        }

        for (binding <- bindings.get(clazz).asScala) {
            val oldThoseIds = binding.getFwdReferenceAsList(snapshot.obj).asScala
            val newThoseIds = binding.getFwdReferenceAsList(newThisObj).asScala

            for (removedThatId <- oldThoseIds -- newThoseIds)
                clearBackreference(binding, thisId, removedThatId)
            for (addedThatId <- newThoseIds -- oldThoseIds)
                addBackreference(binding, thisId, addedThatId)
        }

        updateCacheAndOp(clazz, thisId, snapshot, newThisObj)
    }

    /**
     * Updates the cached object with the specified object.
     */
    private def updateCacheAndOp(clazz: Class[_], id: ObjId, obj: Obj)
    : Unit = {
        val snapshot = cachedGet(clazz, id).getOrElse(
            throw new NotFoundException(clazz, id))
        updateCacheAndOp(clazz, id, snapshot, obj)
    }

    /**
     * Updates the cached object with the specified object, and owner. This
     * method requires the current object snapshot.
     */
    private def updateCacheAndOp(clazz: Class[_], id: ObjId,
                                 snapshot: ObjSnapshot, obj: Obj): Unit = {
        val key = getKey(clazz, id)

        ops.get(key) match {
            case None =>
                // No previous op: add a TxUpdate with new object
                ops += key -> TxUpdate(obj, snapshot.version)
            case Some(TxCreate(_)) =>
                // Previous create: add a TxCreate with new object
                ops(key) = TxCreate(obj)
            case Some(TxUpdate(_,_)) =>
                // Previous update: add a TxUpdate with new object
                ops(key) = TxUpdate(obj, snapshot.version)
            case Some(_) =>
                throw new NotFoundException(key.clazz, key.id)
        }
        objCache(key) = Some(ObjSnapshot(obj, snapshot.version))
    }

    /* If ignoresNeo (ignores deletion on non-existing objects) is true,
     * the method silently returns if the specified object does not exist /
     * has already been deleted.
     */
    @throws[NotFoundException]
    @throws[InternalObjectMapperException]
    @throws[ConcurrentModificationException]
    override def delete(clazz: Class[_], id: ObjId, ignoresNeo: Boolean): Unit = {
        assert(isRegistered(clazz))
        val key = getKey(clazz, id)

        val ObjSnapshot(thisObj, thisVersion) = try {
            cachedGet(clazz, id) match {
                case Some(s) => s
                case None if ignoresNeo => return
                // The primary purpose of this is to throw up a red flag
                // when the caller explicitly tries to delete the same
                // object twice in a single multi() call. This will throw an
                // exception if an object is deleted twice via cascading delete.
                // This is intentional; cascading delete implies an ownership
                // relationship, and it doesn't make sense for an object to have
                // two owners unless explicitly requested for idempotent
                // deletion.
                case None => throw new NotFoundException(clazz, id)
            }
        } catch {
            case nfe: NotFoundException if ignoresNeo => return
        }

        ops.get(key) match {
            case None =>
                // No previous op: add a TxDelete
                ops += key -> TxDelete(thisVersion)
            case Some(TxCreate(_)) =>
                // Previous create: remove the op
                ops -= key
            case Some(TxUpdate(_, ver)) =>
                // Previous update: add a TxDelete
                ops(key) = TxDelete(ver)
            case Some(op) =>
                throw new InternalObjectMapperException(
                    s"Unexpected op $op for delete", null)
        }
        objCache(key) = None

            // Do not remove bindings if the object was not deleted
        if (objCache(key).isDefined) {
            return
        }

        val thisId = getObjectId(thisObj)
        for (binding <- bindings.get(key.clazz).asScala
             if binding.hasBackReference;
             thatId <- binding.getFwdReferenceAsList(thisObj).asScala.distinct
             if !isDeleted(getKey(binding.getReferencedClass, thatId))) {

            binding.onDeleteThis match {
                case DeleteAction.ERROR =>
                    throw new ObjectReferencedException(
                        key.clazz, key.id, binding.getReferencedClass, thatId)
                case DeleteAction.CLEAR =>
                    clearBackreference(binding, thisId, thatId)
                case DeleteAction.CASCADE =>
                    // Breaks if A has bindings with cascading delete to B
                    // and C, and B has a binding to C with ERROR semantics.
                    // This would be complicated to fix and probably isn't
                    // needed, so I'm leaving it as is.
                    //
                    // Cascading delete always takes precedence over object
                    // ownership, by deleting the referenced object regardless
                    // of its current owners.
                    delete(binding.getReferencedClass, thatId, ignoresNeo)
            }
        }
    }

    /**
     * Flattens the current operations in a single key-op sequence.
     */
    protected def flattenOps: Seq[(Key, TxOp)] = {
        if (nodeOps.isEmpty) {
            // Fast path if there are no node ops.
            return ops.toSeq
        }

        // Get node operations: the iterator produces them in breadth-first
        // order. The toList call is needed to make collect preserve the
        // breadth-first order. Then, the delete operations need are sorted such
        // that children are deleted before their parents. Simply reversing them
        // should do this, since they were already in top-down breadth-first
        // order.
        val (delOps, otherOps) = nodeOps.toList.collect {
            // TxNodeExists doesn't correspond to a real operation.
            case (path, op) if op != TxNodeExists =>
                (Key(null, path), op)
        } partition { _._2 == TxDeleteNode }

        val list = new ArrayBuffer[(Key, TxOp)](ops.size + otherOps.length +
                                                delOps.length)

        list ++= ops
        list ++= otherOps
        list ++= delOps.reverse

        list.toSeq
    }

    /** Creates a new data node as part of the current transaction. */
    @throws[StorageNodeExistsException]
    override def createNode(path: String, value: String): Unit = {
        nodeOps.get(path) match {
            case None =>
                val cn = TxCreateNode(value)
                nodeOps(path) = cn
                ensureNode(parentPath(path))
            case Some(TxDeleteNode) =>
                nodeOps(path) = new TxUpdateNode(value)
                ensureNode(parentPath(path))
            case Some(TxCreateNode(_) | TxUpdateNode(_) | TxNodeExists) =>
                throw new StorageNodeExistsException(path)
        }
    }

    /**
     * Ensure that the specified node exists, adding create operations for
     * it and any ancestor nodes that do not yet exist.
     */
    private def ensureNode(path: String): Unit = {
        nodeOps.get(path) match {
            case None => // Don't know whether this node exists.
                if (nodeExists(path)) {
                    nodeOps(path) = TxNodeExists
                } else {
                    val cn = TxCreateNode(null)
                    nodeOps(path) = cn
                    ensureNode(parentPath(path))
                }
            case Some(TxDeleteNode) =>
                nodeOps(path) = TxUpdateNode(null)
                ensureNode(parentPath(path))
            case Some(TxCreateNode(_) | TxUpdateNode(_) | TxNodeExists) =>
                // Already exists.
        }
    }

    /** Updates a data node as part of the current transaction. */
    @throws[StorageNodeNotFoundException]
    override def updateNode(path: String, value: String): Unit = {
        nodeOps.get(path) match {
            case None | Some(TxUpdateNode(_) | TxNodeExists) =>
                nodeOps(path) = TxUpdateNode(value)
            case Some(TxCreateNode(_)) =>
                nodeOps(path) = TxCreateNode(value)
            case Some(TxDeleteNode) =>
                throw new StorageNodeNotFoundException(path)
        }
    }

    /** Deletes a data node as part of the current transaction. */
    override def deleteNode(path: String, idempotent: Boolean): Unit = {

        // First mark all known descendants for deletion.
        for ((p, op) <- nodeOps.getDescendants(path)) op match {
            case TxCreateNode(_) => nodeOps -= p
            case TxUpdateNode(_) | TxNodeExists =>
                nodeOps(p) = TxDeleteNode
            case TxDeleteNode =>
        }

        if (idempotent && !nodeExists(path)) {
            return
        }

        // Mark the node itself for deletion.
        if (nodeOps.get(path).isEmpty) nodeOps(path) = TxDeleteNode

        // Now add delete operations for all remaining descendant nodes in
        // Zookeeper that we don't know about yet.
        def recDelete(path: String): Unit = {
            for (child <- childrenOf(path) if nodeOps.get(child).isEmpty) {
                nodeOps(child) = TxDeleteNode
                recDelete(child)
            }
        }
        recDelete(path)
    }

    private def parentPath(path: String): String = {
        if (path.head != '/' || path.last == '/')
            throw new IllegalArgumentException("Invalid path: " + path)
        val i = path.lastIndexOf('/')
        if (i == 0) "/" else path.substring(0, i)
    }

    /** Query the backend store to determine if a node exists at the
      * specified path. */
    protected def nodeExists(path: String): Boolean

    /** Query the backend store to get the fully-qualified paths of all
      * children of the specified node. */
    protected def childrenOf(path: String): Seq[String]
}
