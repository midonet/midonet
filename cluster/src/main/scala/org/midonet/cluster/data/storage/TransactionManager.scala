/*
 * Copyright 2014 Midokura SARL
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

import org.midonet.cluster.data._
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction
import org.midonet.cluster.data.storage.TransactionManager.{ClassesMap, BindingsMap}

object TransactionManager {

    type ClassesMap = Map[Class[_], ClassInfo]
    type BindingsMap = Multimap[Class[_], FieldBinding]

    case class Key(clazz: Class[_], id: ObjId)
    case class ObjSnapshot(obj: Obj, version: Int, owners: Set[String])

    trait TxOp
    trait TxOwnerOp extends TxOp { def owner: String }
    case class TxCreate(obj: Obj, ops: Seq[TxOwnerOp]) extends TxOp
    case class TxUpdate(obj: Obj, version: Int, ops: Seq[TxOwnerOp]) extends TxOp
    case class TxDelete(version: Int, ops: Seq[TxOwnerOp]) extends TxOp
    case class TxCreateOwner(owner: String) extends TxOwnerOp
    case class TxDeleteOwner(owner: String) extends TxOwnerOp

    private final val NewObjectVersion = -1
}

/**
 * Provides basic support for a storage transaction. The manager consolidates
 * a set of atomic operations.
 * @param classes The map of registered classes.
 * @param bindings The map of registered bindings.
 */
abstract class TransactionManager(classes: ClassesMap, bindings: BindingsMap) {

    import TransactionManager._

    // This is a transaction-local cache of the objects to modify. This
    // cache is only used during the lifetime of the transaction, and all
    // modifications are applied to the cached copies, until the commit.
    // If, because of a concurrent modification, any of the cached objects
    // become out-of-date, the transaction will fail.
    private val objCache = new mutable.HashMap[Key, Option[ObjSnapshot]]()
    // This is an ordered map of all operations to be applied to ZooKeeper
    // by this transaction. Each operation specified by the user will
    // update the list of operations in this list, such there is only
    // a single per-object operation committed to ZooKeeper. Therefore, for
    // any user operation, the transaction will create, update or delete
    // a ZK operation in this ordered map. The changes apply as follows:
    //
    // create + None -> TxCreate
    //        + Some(TxDelete) -> TxUpdate
    //        + _ -> throws ObjectExistsException
    // update + None -> TxUpdate
    //        + Some(TxCreate) -> TxCreate
    //        + Some(TxUpdate) -> TxUpdate
    //        + _ -> NotFoundException
    // delete + None -> if(no owners) TxDelete else TxUpdate
    //        + Some(TxCreate) -> if(no owners) None else TxCreate
    //        + Some(TxUpdate) -> if(no owners) TxDelete else TxUpdate
    //        + _ -> do nothing (idempotent delete)
    //
    // Modifying the owner, involves adding a TxDeleteOwner if the owner
    // is already found in the list of owners, followed by a
    // TxCreateOwner. These are added to the ownership operations for
    // each object operation.
    protected val ops = new mutable.LinkedHashMap[Key, TxOp]

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
        objCache.getOrElseUpdate(Key(clazz, id),
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
        val key = Key(obj.getClass, thisId)

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
        update(obj, None, false, validator)
    }

    def update(obj: Obj, owner: String, overwrite: Boolean,
               validator: UpdateValidator[Obj]): Unit = {
        update(obj, Some(owner), overwrite, validator)
    }

    private def update(obj: Obj, owner: Option[String], overwrite: Boolean,
                       validator: UpdateValidator[Obj]): Unit = {

        val clazz = obj.getClass
        assert(isRegistered(clazz))

        val thisId = getObjectId(obj)
        val os = get(clazz, thisId).getOrElse(
            throw new NotFoundException(clazz, thisId))

        if (owner.isEmpty && classes(clazz).ownershipType.isExclusive &&
            os.owners.nonEmpty) {
            throw new UnsupportedOperationException(
                "Update not supported because owner is not specified")
        }
        if (owner.isDefined && classes(clazz).ownershipType.isExclusive &&
            !os.owners.contains(owner.get) && os.owners.nonEmpty) {
            throw new OwnershipConflictException(
                clazz.getSimpleName, thisId.toString, os.owners, owner.get,
                "Caller does not own object")
        }
        if (owner.isDefined && !overwrite &&
            os.owners.contains(owner.get)) {
            throw new OwnershipConflictException(
                clazz.getSimpleName, thisId.toString, os.owners, owner.get,
                "Ownership already exists")
        }

        // Invoke the validator/update callback if provided. If it returns
        // a modified object, use that in place of obj for the update.
        val newThisObj = if (validator == null) obj else {
            val modified = validator.validate(os.obj, obj)
            val thisObj = if (modified != null) modified else obj
            if (getObjectId(thisObj) != thisId) {
                throw new IllegalArgumentException(
                    "Modifying newObj.id in UpdateValidator.validate() " +
                    "is not supported.")
            }
            thisObj
        }

        for (binding <- bindings.get(clazz).asScala) {
            val oldThoseIds = binding.getFwdReferenceAsList(os.obj).asScala
            val newThoseIds = binding.getFwdReferenceAsList(newThisObj).asScala

            for (removedThatId <- oldThoseIds -- newThoseIds)
                clearBackreference(binding, thisId, removedThatId)
            for (addedThatId <- newThoseIds -- oldThoseIds)
                addBackreference(binding, thisId, addedThatId)
        }

        updateCacheAndOp(clazz, thisId, os, newThisObj, owner)
    }

    def updateOwner(clazz: Class[_], id: ObjId, owner: String,
                    overwrite: Boolean): Unit = {
        assert(isRegistered(clazz))
        val os = get(clazz, id).getOrElse(throw new NotFoundException(clazz, id))

        if (classes(clazz).ownershipType.isExclusive &&
            !os.owners.contains(owner) && os.owners.nonEmpty) {
            throw new OwnershipConflictException(
                clazz.getSimpleName, id.toString, os.owners, owner,
                "Caller does not own object")
        }
        if (!overwrite && os.owners.contains(owner) && os.owners.nonEmpty) {
            throw new OwnershipConflictException(
                clazz.getSimpleName, id.toString,
                os.owners, owner, "Ownership already exists")
        }

        updateCacheAndOp(clazz, id, os, os.obj, Some(owner))
    }

    /**
     * Updates the cached object with the specified object.
     */
    private def updateCacheAndOp(clazz: Class[_], id: ObjId, obj: Obj)
    : Unit = {
        val os = get(clazz, id).getOrElse(
            throw new NotFoundException(clazz, id))
        updateCacheAndOp(clazz, id, os, obj, None)
    }

    /**
     * Updates the cached object with the specified object, and owner. This
     * method requires the current object snapshot.
     */
    private def updateCacheAndOp(clazz: Class[_], id: ObjId,
                                 os: ObjSnapshot, obj: Obj,
                                 owner: Option[String]): Unit = {
        val key = Key(clazz, id)

        ops.get(key) match {
            case None =>
                // No previous op: add a TxUpdate with an optional
                // TxDeleteOwner if the owner existed, and a TxCreateOwner
                val ownerOps = updateOwnerOps(os.owners, owner)
                ops += key -> TxUpdate(obj, os.version, ownerOps)

            case Some(TxCreate(_, o)) =>
                // Previous create: add a TxCreate with all the previous
                // ownership ops, an optional TxDeleteOwner if the owner
                // existed, and a TxCreateOwner
                val ownerOps = o ++ updateOwnerOps(os.owners, owner)
                ops(key) = TxCreate(obj, ownerOps)
            case Some(TxUpdate(_, _, o)) =>
                // Previous update: add a TxUpdate with all the previous
                // ownership ops, an optional TxDeleteOwner if the owner
                // existed, and a TxCreateOwner
                val ownerOps = o ++ updateOwnerOps(os.owners, owner)
                ops(key) = TxUpdate(obj, os.version, ownerOps)
            case Some(_) =>
                throw new NotFoundException(key.clazz, key.id)
        }
        objCache(key) =
            Some(ObjSnapshot(obj, os.version, os.owners ++ owner))
    }

    /* If ignoresNeo (ignores deletion on non-existing objects) is true,
     * the method silently returns if the specified object does not exist /
     * has already been deleted.
     */
    def delete(clazz: Class[_], id: ObjId, ignoresNeo: Boolean,
               owner: Option[String]): Unit = {
        assert(isRegistered(clazz))
        val key = Key(clazz, id)

        val ObjSnapshot(thisObj, thisVersion, thisOwners) = try {
            get(clazz, id) match {
                case Some(s) => s
                case None if ignoresNeo => return
                // The primary purpose of this is to throw up a red flag
                // when the caller explicitly tries to delete the same
                // object twice in a single multi() call, but this will
                // throw an exception if an object is deleted twice via
                // cascading delete. This is intentional; cascading delete
                // implies an ownership relationship, and it doesn't make
                // sense for an object to have two owners unless explicitly
                // requested for idempotent deletion.
                case None => throw new NotFoundException(clazz, id)
            }
        } catch {
            case nfe: NotFoundException if ignoresNeo => return
        }

        if (classes(clazz).ownershipType.isExclusive) {
            if (thisOwners.nonEmpty && owner.isEmpty) {
                throw new UnsupportedOperationException(
                    "Delete not supported because owner is not specified")
            }
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
                // - add a TxCreate, keeping all previous ownership ops
                // and a TxDeleteOwner for the specified owners
                val ownerOps = o ++ ownersToDelete.map(TxDeleteOwner)
                if (newOwners.isEmpty) {
                    ops -= key
                    None
                } else {
                    ops(key) = TxCreate(obj, ownerOps)
                    Some(ObjSnapshot(thisObj, thisVersion, newOwners))
                }
            case Some(TxUpdate(obj, ver, o)) =>
                // Previous update: if the set of owners is empty:
                // - add a TxDelete
                // - add a TxUpdate
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

        for (binding <- bindings.get(key.clazz).asScala
             if binding.hasBackReference;
             thatId <- binding.getFwdReferenceAsList(thisObj).asScala.distinct
             if !isDeleted(Key(binding.getReferencedClass, thatId))) {

            binding.onDeleteThis match {
                case DeleteAction.ERROR =>
                    throw new ObjectReferencedException(
                        key.clazz, key.id, binding.getReferencedClass, thatId)
                case DeleteAction.CLEAR =>
                    clearBackreference(binding, key.id, thatId)
                case DeleteAction.CASCADE =>
                    // Breaks if A has bindings with cascading delete to B
                    // and C, and B has a binding to C with ERROR semantics.
                    // This would be complicated to fix and probably isn't
                    // needed, so I'm leaving it as is.
                    delete(binding.getReferencedClass, thatId, ignoresNeo, owner)
            }
        }
    }

    def deleteOwner(clazz: Class[_], id: ObjId, owner: String): Unit = {
        assert(isRegistered(clazz))
        val key = Key(clazz, id)
        val ObjSnapshot(thisObj, thisVersion, thisOwners) =
            get(clazz, id).getOrElse(
                throw new NotFoundException(clazz, id))

        if (!thisOwners.contains(owner)) {
            throw new OwnershipConflictException(
                clazz.getSimpleName, id.toString, thisOwners, owner,
                "Caller does not own object")
        }
        if (classes(clazz).ownershipType.isExclusive) {
            throw new OwnershipConflictException(
                clazz.getSimpleName, id.toString, thisOwners, owner,
                "Cannot delete the owner from an exclusive class")
        }

        val owners = thisOwners - owner
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
        for (op <- ops) op._2 match {
            case TxCreate(_, ownerOps) =>
                list += op._1 -> op._2
                list ++= ownerOps.map(op._1 -> _)
            case TxUpdate(_, _, ownerOps) =>
                list += op._1 -> op._2
                list ++= ownerOps.map(op._1 -> _)
            case TxDelete(_, ownerOps) =>
                list ++= ownerOps.map(op._1 -> _)
                list += op._1 -> op._2
        }
        list
    }

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

}
