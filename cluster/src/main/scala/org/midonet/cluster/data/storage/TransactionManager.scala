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

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

import com.google.common.collect.Multimap
import com.google.protobuf.Message

import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.TransactionManager.{ClassesMap, BindingsMap}

object TransactionManager {

    type ClassesMap = Map[Class[_], ClassInfo]
    type BindingsMap = Multimap[Class[_], FieldBinding]

    case class Key(clazz: Class[_], id: String)
    val NullKey = Key(null, null)

    case class ObjSnapshot(obj: Obj, version: Int, owners: Set[String])

    sealed trait TxOp
    sealed trait TxOwnerOp extends TxOp { def owner: String }
    sealed trait TxMapOp extends TxOp
    sealed trait TxMapEntryOp extends TxOp
    case class TxCreate(obj: Obj, ops: Seq[TxOwnerOp]) extends TxOp
    case class TxUpdate(obj: Obj, version: Int, ops: Seq[TxOwnerOp]) extends TxOp
    case class TxDelete(version: Int, ops: Seq[TxOwnerOp]) extends TxOp
    case class TxCreateOwner(owner: String) extends TxOwnerOp
    case class TxDeleteOwner(owner: String) extends TxOwnerOp

    class TxCreateMap(val mapId: String) extends TxMapOp {
        val entryOps = new mutable.HashMap[String, TxMapEntryOp]
    }
    class TxUpdateMap(val mapId: String) extends TxMapOp {
        val entryOps = new mutable.HashMap[String, TxMapEntryOp]
    }
    case class TxDeleteMap(mapId: String) extends TxMapOp

    case class TxAddMapEntry(key: String, value: String) extends TxMapEntryOp
    case class TxUpdateMapEntry(key: String, value: String) extends TxMapEntryOp
    case class TxDeleteMapEntry(key: String) extends TxMapEntryOp

    private final val NewObjectVersion = -1

    @inline
    private[storage] def getIdString(clazz: Class[_], id: ObjId): String = {
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
abstract class TransactionManager(classes: ClassesMap, bindings: BindingsMap) {

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

    protected val mapOps = new mutable.HashMap[String, TxMapOp]

    protected def isRegistered(clazz: Class[_]): Boolean

    protected def getSnapshot(clazz: Class[_], id: ObjId): ObjSnapshot

    protected def commit(): Unit

    /**
     * Gets the specified object from the internal cache. If not found,
     * loads it from Zookeeper and caches it.
     *
     * @return None if the object has been marked for deletion.
     */
    private def get(clazz: Class[_], id: ObjId): Option[ObjSnapshot] = {
        objCache.getOrElseUpdate(getKey(clazz, id),
                                 Some(getSnapshot(clazz, id)))
    }

    private def getObjectId(obj: Obj) = classes(obj.getClass).idOf(obj)

    private def isDeleted(key: Key): Boolean = ops.get(key) match {
        case Some(TxDelete(_,_)) => true
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
        get(bdg.getReferencedClass, thatId).foreach { snapshot =>
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
        get(bdg.getReferencedClass, thatId).foreach { snapshot =>
            val updatedThatObj = bdg.clearBackReference(snapshot.obj, thisId)
            updateCacheAndOp(bdg.getReferencedClass, thatId,
                             updatedThatObj.asInstanceOf[Obj])
                                                         }
    }

    def create(obj: Obj): Unit = create(obj, None)

    def create(obj: Obj, owner: String): Unit = create(obj, Some(owner))

    private def create(obj: Obj, owner: Option[String]): Unit = {
        assert(isRegistered(obj.getClass))

        if (classes(obj.getClass).ownershipType.isExclusive &&
            owner.isEmpty) {
            throw new UnsupportedOperationException(
                s"Class ${obj.getClass.getSimpleName} requires owner")
        }

        val thisId = getObjectId(obj)
        val key = getKey(obj.getClass, thisId)

        if(objCache.contains(key) && !isDeleted(key)) {
            throw new ObjectExistsException(key.clazz, key.id)
        }

        objCache(key) = ops.get(key) match {
            case None =>
                // No previous op: add a TxCreate with an optional
                // TxCreateOwner
                val ownerOps = owner.map(TxCreateOwner).toSeq
                ops += key -> TxCreate(obj, ownerOps)
                Some(ObjSnapshot(obj, NewObjectVersion, owner.toSet))
            case Some(TxDelete(ver, o)) =>
                // Previous delete: add a TxUpdate, keeping all previous
                // owner ops and adding a new TxCreateOwner
                val ownerOps = o ++ owner.map(TxCreateOwner).toSeq
                ops(key) = TxUpdate(obj, ver, ownerOps)
                Some(ObjSnapshot(obj, ver, owner.toSet))
            case Some(_) =>
                throw new ObjectExistsException(key.clazz, key.id)
        }

        for (binding <- bindings.get(obj.getClass).asScala;
             thatId <- binding.getFwdReferenceAsList(obj).asScala) {
            addBackreference(binding, thisId, thatId)
        }
    }

    def update(obj: Obj, validator: UpdateValidator[Obj]): Unit = {
        update(obj, None, validator)
    }

    def update(obj: Obj, owner: String, validator: UpdateValidator[Obj])
    : Unit = {
        update(obj, Some(owner), validator)
    }

    private def update(obj: Obj, owner: Option[String],
                       validator: UpdateValidator[Obj]): Unit = {

        val clazz = obj.getClass
        assert(isRegistered(clazz))

        val thisId = getObjectId(obj)
        val snapshot = get(clazz, thisId).getOrElse(
            throw new NotFoundException(clazz, thisId))

        validateOwner(clazz, thisId, snapshot.owners, owner, false)

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

        updateCacheAndOp(clazz, thisId, snapshot, newThisObj, owner)
    }

    def updateOwner(clazz: Class[_], id: ObjId, owner: String,
                    throwIfExists: Boolean): Unit = {
        assert(isRegistered(clazz))
        val snapshot = get(clazz, id)
            .getOrElse(throw new NotFoundException(clazz, id))

        validateOwner(clazz, id, snapshot.owners, Some(owner), throwIfExists)
        updateCacheAndOp(clazz, id, snapshot, snapshot.obj, Some(owner))
    }

    /**
     * Updates the cached object with the specified object.
     */
    private def updateCacheAndOp(clazz: Class[_], id: ObjId, obj: Obj)
    : Unit = {
        val snapshot = get(clazz, id).getOrElse(
            throw new NotFoundException(clazz, id))
        updateCacheAndOp(clazz, id, snapshot, obj, None)
    }

    /**
     * Updates the cached object with the specified object, and owner. This
     * method requires the current object snapshot.
     */
    private def updateCacheAndOp(clazz: Class[_], id: ObjId,
                                 snapshot: ObjSnapshot, obj: Obj,
                                 owner: Option[String]): Unit = {
        val key = getKey(clazz, id)

        val ownerOps = updateOwnerOps(snapshot.owners, owner)
        ops.get(key) match {
            case None =>
                // No previous op: add a TxUpdate with an optional
                // TxDeleteOwner if the owner existed, and a TxCreateOwner
                ops += key -> TxUpdate(obj, snapshot.version, ownerOps)
            case Some(TxCreate(_, o)) =>
                // Previous create: add a TxCreate with all the previous
                // ownership ops, an optional TxDeleteOwner if the owner
                // existed, and a TxCreateOwner
                ops(key) = TxCreate(obj, o ++ ownerOps)
            case Some(TxUpdate(_, _, o)) =>
                // Previous update: add a TxUpdate with all the previous
                // ownership ops, an optional TxDeleteOwner if the owner
                // existed, and a TxCreateOwner
                ops(key) = TxUpdate(obj, snapshot.version, o ++ ownerOps)
            case Some(_) =>
                throw new NotFoundException(key.clazz, key.id)
        }
        objCache(key) =
            Some(ObjSnapshot(obj, snapshot.version, snapshot.owners ++ owner))
    }

    /* If ignoresNeo (ignores deletion on non-existing objects) is true,
     * the method silently returns if the specified object does not exist /
     * has already been deleted.
     */
    def delete(clazz: Class[_], id: ObjId, ignoresNeo: Boolean,
               owner: Option[String]): Unit = {
        assert(isRegistered(clazz))
        val key = getKey(clazz, id)

        val ObjSnapshot(thisObj, thisVersion, thisOwners) = try {
            get(clazz, id) match {
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

        if (classes(clazz).ownershipType.isExclusive && thisOwners.nonEmpty &&
            owner.isEmpty) {
            throw new UnsupportedOperationException(
                "Delete not supported because owner is not specified")
        }
        val ownersToDelete: Set[String] = owner match {
            case Some(o) if !thisOwners.contains(o) =>
                throw new OwnershipConflictException(
                    clazz.getSimpleName, id.toString, thisOwners,
                    o, s"Delete not supported because $o is not owner")
            case Some(_) => // Otherwise, delete the specified owner.
                owner.toSet
            case None => // If no owner specified, delete all owners.
                thisOwners
        }

        val newOwners = thisOwners -- ownersToDelete
        objCache(key) = ops.get(key) match {
            case None =>
                // No previous op: if the set of owners is empty:
                // - add a TxDelete with TxDeleteOwner for specified owners
                // - else, add a TxUpdate with TxDeleteOwner for specified
                // owners
                val ownerOps = ownersToDelete.map(TxDeleteOwner).toSeq
                if (newOwners.isEmpty) {
                    ops += key -> TxDelete(thisVersion, ownerOps)
                    None
                } else {
                    ops += key -> TxUpdate(thisObj, thisVersion, ownerOps)
                    Some(ObjSnapshot(thisObj, thisVersion, newOwners))
                }
            case Some(TxCreate(obj, o)) =>
                // Previous create: if the set of owners is empty:
                // - remove the op
                // - else, add a TxCreate, keeping all previous ownership ops
                // and a TxDeleteOwner for the specified owners
                if (newOwners.isEmpty) {
                    ops -= key
                    None
                } else {
                    val ownerOps = o ++ ownersToDelete.map(TxDeleteOwner)
                    ops(key) = TxCreate(obj, ownerOps)
                    Some(ObjSnapshot(thisObj, thisVersion, newOwners))
                }
            case Some(TxUpdate(obj, ver, o)) =>
                // Previous update: if the set of owners is empty:
                // - add a TxDelete
                // - else, add a TxUpdate
                // Both cases keep all previus ownership ops and a
                // TxDeleteOwner for the specified owners
                val ownerOps = o ++ ownersToDelete.map(TxDeleteOwner)
                if (newOwners.isEmpty) {
                    ops(key) = TxDelete(ver, ownerOps)
                    None
                } else {
                    ops(key) = TxUpdate(obj, ver, ownerOps)
                    Some(ObjSnapshot(thisObj, thisVersion, newOwners))
                }
            case Some(_) => throw new InternalError()
        }

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
                    delete(binding.getReferencedClass, thatId, ignoresNeo, None)
            }
        }
    }

    def deleteOwner(clazz: Class[_], id: ObjId, owner: String): Unit = {
        assert(isRegistered(clazz))
        val ObjSnapshot(thisObj, thisVersion, thisOwners) =
            get(clazz, id).getOrElse(
                throw new NotFoundException(clazz, id))

        if (!thisOwners.contains(owner)) {
            throw new OwnershipConflictException(
                clazz.getSimpleName, id.toString, thisOwners, owner,
                "Caller does not own object")
        }

        val owners = thisOwners - owner
        val key = getKey(clazz, id)
        ops.get(key) match {
            case None =>
                // No previous op: add a TxUpdate with a TxDeleteOwner for
                // the specified owner
                ops += key -> TxUpdate(thisObj, thisVersion,
                                       Seq(TxDeleteOwner(owner)))
            case Some(TxCreate(obj, o)) =>
                // Previous create: add a TxCreate, keeping all previous
                // ownership ops and a TxDeleteOwner for the specified owner
                ops(key) = TxCreate(obj, o :+ TxDeleteOwner(owner))
            case Some(TxUpdate(obj, ver, o)) =>
                // Previous update: add a TxUpdate, keeping all previous
                // ownership ops and a TxDeleteOwner for the specified owner
                ops(key) = TxUpdate(obj, ver, o :+ TxDeleteOwner(owner))
            case Some(_) =>
                throw new NotFoundException(clazz, id)
        }
        objCache(key) = Some(ObjSnapshot(thisObj, thisVersion, owners))
    }

    /**
     * Flattens the current operations in a single key-op sequence.
     */
    protected def flattenOps: Seq[(Key, TxOp)] = {
        val list = new ListBuffer[(Key, TxOp)]
        for ((key, txOp) <- ops) txOp match {
            case TxCreate(_, ownerOps) =>
                list += key -> txOp
                list ++= ownerOps.map(key -> _)
            case TxUpdate(_, _, ownerOps) =>
                list += key -> txOp
                list ++= ownerOps.map(key -> _)
            case TxDelete(_, ownerOps) =>
                list ++= ownerOps.map(key -> _)
                list += key -> txOp
            case _ =>
        }

        for (op <- mapOps) op._2 match {
            case cm: TxCreateMap =>
                // We could use a key with String as the class and mapId or key
                // as the ID (for maps and entries, respectively), but it
                // wouldn't add anything, since the operations themselves
                // contain that information.
                list ++= flattenCreateMap(cm).map(NullKey -> _)
                for (eo <- cm.entryOps.values)
                    list += Key(null, cm.mapId) -> eo
            case um: TxUpdateMap =>
                for (eo <- um.entryOps.values)
                    list += Key(null, um.mapId) -> eo
            case dm: TxDeleteMap =>
                 list ++= flattenDeleteMap(dm).map(NullKey -> _)
        }

        list.toList
    }

    /**
     * Flatten a TxDeleteMap operation into a sequence of operations. Needed
     * because some storage engines *cough* Zookeeper *cough* need to have
     * every little thing spelled out to them and don't allow deleting nodes
     * until you delete all their children.
     *
     * Default implementation is to pretend we knew better than to use Zookeeper
     * as a database, and just return the TxDeleteMap. Gory details in
     * ZookeeperObjectMapper override.
     */
    protected def flattenDeleteMap(tx: TxDeleteMap): List[TxOp] = List(tx)

    /**
     * Same story as flattenDeleteMap, except this time it's that Zookeeper
     * won't allow recursive creates, e.g., to create /maps/bridges/1/arp, we
     * must first explicitly create /maps, /maps/bridges, and /maps/bridges/1.
     */
    protected def flattenCreateMap(tx: TxCreateMap): List[TxOp] = List(tx)

    /**
     * Creates a list of transaction operations when updating the owner of
     * an object.
     * @param owners The current object owners.
     * @param owner Some(owner) when a new owner is specified, or None when
     *              the ownership is not changed.
     */
    private def updateOwnerOps(owners: Set[String], owner: Option[String])
    : Seq[TxOwnerOp] = owner match {
        case Some(o) if owners.contains(o) =>
            Seq(TxDeleteOwner(o), TxCreateOwner(o))
        case Some(o) => Seq(TxCreateOwner(o))
        case None => Seq.empty[TxOwnerOp]
    }

    /**
     * Validates the owner for an ownership update.
     * @param owners The set of current owners.
     * @param owner The new owner or None, if no owner is specified.
     * @param throwIfExists Throws an exception of the ownership node
     *                      already exists.
     */
    private def validateOwner(clazz: Class[_], id: ObjId,
                              owners: Set[String], owner: Option[String],
                              throwIfExists: Boolean): Unit = {
        if (classes(clazz).ownershipType.isExclusive && owners.nonEmpty) {
            if (owner.isEmpty) {
                throw new UnsupportedOperationException(
                    "Update not supported because owner is not specified")
            } else if (owner.isDefined && !owners.contains(owner.get)) {
                throw new OwnershipConflictException(
                    clazz.getSimpleName, id.toString, owners, owner.get,
                    "Caller does not own object")
            }
        }
        if (owner.isDefined && throwIfExists && owners.contains(owner.get)) {
            throw new OwnershipConflictException(
                clazz.getSimpleName, id.toString, owners, owner.get,
                "Ownership already exists")
        }
    }

    def createMap(mapId: String): Unit = {
        // No support for recreating a map that was deleted in the same
        // transaction. This could be done, but we'd need to add an additional
        // operation for clearing the map, and I don't anticipate needing
        // support for this.
        mapOps.get(mapId) match {
            case None => mapOps(mapId) = new TxCreateMap(mapId)
            case Some(_) => throw new MapExistsException(mapId)
        }
    }

    def deleteMap(mapId: String): Unit = {
        mapOps.get(mapId) match {
            case Some(TxDeleteMap(_)) => throw new MapNotFoundException(mapId)
            case _ => mapOps(mapId) = TxDeleteMap(mapId)
        }
    }

    private def getEntryMap(mapId: String)
    : mutable.Map[String, TxMapEntryOp] = mapOps.get(mapId) match {
        case Some(tx: TxCreateMap) => tx.entryOps
        case Some(tx: TxUpdateMap) => tx.entryOps
        case Some(TxDeleteMap(_)) => throw new MapNotFoundException(mapId)
        case None =>
            val tx = new TxUpdateMap(mapId)
            mapOps(mapId) = tx
            tx.entryOps
    }

    def addMapEntry(mapId: String, key: String, value: String): Unit = {
        val entryOps = getEntryMap(mapId)
        entryOps(key) = entryOps.get(key) match {
            case Some(TxAddMapEntry(_, _)) | Some(TxUpdateMapEntry(_, _)) =>
                throw new MapEntryExistsException(mapId, key)
            case Some(TxDeleteMapEntry(_)) => TxUpdateMapEntry(key, value)
            case None => TxAddMapEntry(key, value)
        }
    }

    def updateMapEntry(mapId: String, key: String, value: String): Unit = {
        val entryOps = getEntryMap(mapId)
        entryOps(key) = entryOps.get(key) match {
            case Some(TxAddMapEntry(_, _)) => TxAddMapEntry(key, value)
            case Some(TxUpdateMapEntry(_, _)) => TxUpdateMapEntry(key, value)
            case Some(TxDeleteMapEntry(_)) => TxUpdateMapEntry(key, value)
            case None => TxUpdateMapEntry(key, value)
        }
    }

    def deleteMapEntry(mapId: String, key: String): Unit = {
        val entryOps = getEntryMap(mapId)
        entryOps.get(key) match {
            case Some(TxAddMapEntry(_, _)) =>
                entryOps -= key
            case None | Some(TxUpdateMapEntry(_, _)) =>
                entryOps(key) = TxDeleteMapEntry(key)
            case Some(TxDeleteMapEntry(_)) =>
                throw new MapEntryNotFoundException(mapId, key)
        }
    }


}
