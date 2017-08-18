/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.topology

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.util
import java.util.UUID

import scala.collection.mutable

import org.agrona.concurrent.UnsafeBuffer
import org.slf4j.LoggerFactory

import org.midonet.cluster.cache.{ObjectMessaging, ObjectNotification, StateNotification}
import org.midonet.cluster.data.storage.{MultiValueKey, SingleValueKey, StateStorage}
import org.midonet.cluster.topology.snapshot.TopologySnapshotDecoder.ObjectClassDecoder.ObjectDecoder
import org.midonet.cluster.topology.snapshot.TopologySnapshotDecoder.StateOwnerDecoder.StateClassDecoder
import org.midonet.cluster.topology.snapshot.TopologySnapshotDecoder.StateOwnerDecoder.StateClassDecoder.StateIdDecoder
import org.midonet.cluster.topology.snapshot.TopologySnapshotDecoder.StateOwnerDecoder.StateClassDecoder.StateIdDecoder.StateKeyDecoder
import org.midonet.cluster.topology.snapshot.TopologySnapshotDecoder.{ObjectClassDecoder, StateOwnerDecoder}
import org.midonet.cluster.topology.snapshot.TopologySnapshotEncoder.ObjectClassEncoder.ObjectEncoder
import org.midonet.cluster.topology.snapshot.TopologySnapshotEncoder.StateOwnerEncoder.StateClassEncoder
import org.midonet.cluster.topology.snapshot.TopologySnapshotEncoder.StateOwnerEncoder.StateClassEncoder.StateIdEncoder
import org.midonet.cluster.topology.snapshot.TopologySnapshotEncoder.StateOwnerEncoder.StateClassEncoder.StateIdEncoder.StateKeyEncoder
import org.midonet.cluster.topology.snapshot.TopologySnapshotEncoder.{ObjectClassEncoder, StateOwnerEncoder}
import org.midonet.cluster.topology.snapshot.TopologySnapshotEncoder.StateOwnerEncoder.uuidNullValue
import org.midonet.util.logging.Logger

package object snapshot {

    /**
      * Generic datastructure to store serialized and deserialized data.
      *
      * Although we use the same data structure, serialized data differ
      * from deserialized. Specifically:
      * - Topology objects are serialized using the ZK child data (byte array
      *   of a UTF-8 encoded string) whereas they are deserialized as the
      *   corresponding protobuf [[com.google.protobuf.Message]] object.
      * - State objects are serialized from the TopologySnapshot from a
      *   [[StateUpdate]] whereas they are deserialized as a
      *   [[org.midonet.cluster.data.storage.StateKey]] ready to be used by
      *   the Zoom layer.
      */
    case class TopologySnapshot(objectSnapshot: ObjectSnapshot,
                                stateSnapshot: StateSnapshot)

    type ObjectSnapshot = ObjectNotification.MappedSnapshot
    type ObjectUpdate = ObjectNotification.Update
    type Objects = util.HashMap[Object, Object]

    type StateSnapshot = StateNotification.MappedSnapshot
    type StateUpdate = StateNotification.Update
    type StateClasses = util.HashMap[Class[_], StateIds]
    type StateIds = util.HashMap[Object, StateKeys]
    type StateKeys = util.HashMap[String, Object]

    private val Log = Logger(LoggerFactory.getLogger(
        "org.midonet.nsdb.snapshot-serializer"))

    class TopologySnapshotSerializer {
        val snapshotMessageEncoder = new TopologySnapshotEncoder
        val snapshotHeaderEncoder = new MessageHeaderEncoder
        val snapshotBuffer = new UnsafeBuffer(new Array[Byte](0))


        /**
          * Topology objects grups encoder/decoder helper functions
          */
        private def encodeObjectClass(encoder: ObjectClassEncoder,
                                      snapshot: ObjectSnapshot): Unit = {
            val objs = snapshot.entrySet().iterator()
            while (objs.hasNext) {
                val obj = objs.next()
                encoder.next()
                val objects = encoder.objectCount(obj.getValue.size())
                encodeObject(objects, obj.getValue)
                Log.debug(s"Encoding ${obj.getValue.size()} objects " +
                          s"of class ${obj.getKey.getName}")
                encoder.objectClass(obj.getKey.getName)
            }
        }

        private def encodeObject(encoder: ObjectEncoder,
                                 objects: Objects): Unit = {
            val objs = objects.values.iterator()
            while (objs.hasNext) {
                val obj = objs.next()
                val update = obj.asInstanceOf[ObjectUpdate]
                encoder.next()
                encoder.uuid(0, update.id().getMostSignificantBits)
                encoder.uuid(1, update.id().getLeastSignificantBits)
                encoder.putData(
                    update.childData().getData,
                    0, update.childData().getData.length)
            }
        }


        /**
          * State objects groups encoder/decoder helper functions
          */
        // Owner group encoder/decoder
        private def encodeStateOwner(encoder: StateOwnerEncoder,
                                     snapshot: StateSnapshot): Unit = {
            val entries = snapshot.entrySet().iterator()
            while (entries.hasNext) {
                val entry = entries.next()
                encoder.next()

                val ownerId = if (entry.getKey == null)
                    new UUID(uuidNullValue, uuidNullValue)
                else
                    UUID.fromString(entry.getKey)

                val stateClassMap = entry.getValue
                encoder.uuid(0, ownerId.getMostSignificantBits)
                encoder.uuid(1, ownerId.getLeastSignificantBits)
                val classes = encoder.stateClassCount(stateClassMap.size())
                encodeStateClass(classes, entry.getValue)
            }
        }

        // State classes group encoder/decoder
        private def encodeStateClass(encoder: StateClassEncoder,
                                     objects: StateClasses): Unit = {
            val entries = objects.entrySet().iterator()
            while (entries.hasNext) {
                val entry = entries.next()
                encoder.next()
                val objectIds = encoder.stateIdCount(entry.getValue.size())
                encodeStateId(objectIds, entry.getValue)
                encoder.stateClass(entry.getKey.getName)
            }
        }

        // State object id group encoder/decoder
        private def encodeStateId(encoder: StateIdEncoder,
                                  objects: StateIds): Unit = {
            val entries = objects.entrySet().iterator()
            while (entries.hasNext) {
                val entry = entries.next()
                encoder.next()
                val objectId = entry.getKey.asInstanceOf[UUID]
                encoder.uuid(0, objectId.getMostSignificantBits)
                encoder.uuid(1, objectId.getLeastSignificantBits)
                val stateKeys = encoder.stateKeyCount(entry.getValue.size())
                encodeStateKey(stateKeys, entry.getValue)
            }
        }

        // State keys group encoder/decoder
        private def encodeStateKey(encoder: StateKeyEncoder,
                                   objects: StateKeys): Unit = {
            val entries = objects.entrySet().iterator()
            while (entries.hasNext) {
                val entry = entries.next()
                encoder.next()
                val state = entry.getValue.asInstanceOf[StateUpdate]

                if (state.`type`().isSingle) {
                    encoder.stateType(TopologyStateType.SINGLE)
                } else {
                    encoder.stateType(TopologyStateType.MULTI)
                }

                val values = encoder.multiValueCount(state.multiData().length)

                for (value <- state.multiData()) {
                    values.next().multiValueEntry(value)
                }

                encoder.key(state.key())
                encoder.putSingleValue(
                    state.singleData(), 0, state.singleData().length)
            }
        }

        def serialize(byteArray: Array[Byte],
                      topologySnapshot: TopologySnapshot): Int  = {
            var length = 0

            // Encode header
            snapshotBuffer.wrap(byteArray)
            snapshotHeaderEncoder.wrap(snapshotBuffer, 0)
                .blockLength(snapshotMessageEncoder.sbeBlockLength())
                .templateId(snapshotMessageEncoder.sbeTemplateId())
                .schemaId(snapshotMessageEncoder.sbeSchemaId())
                .version(snapshotMessageEncoder.sbeSchemaVersion())
            length += snapshotHeaderEncoder.encodedLength()

            snapshotMessageEncoder.wrap(snapshotBuffer,
                                        snapshotHeaderEncoder.encodedLength())

            // Encode topology objects
            val classGroups = snapshotMessageEncoder.objectClassCount(
                topologySnapshot.objectSnapshot.size)
            encodeObjectClass(classGroups, topologySnapshot.objectSnapshot)

            // Encode state objects
            val ownerGroups = snapshotMessageEncoder.stateOwnerCount(
                topologySnapshot.stateSnapshot.size)
            encodeStateOwner(ownerGroups, topologySnapshot.stateSnapshot)

            length += snapshotMessageEncoder.encodedLength()
            length
        }
    }

    class TopologySnapshotDeserializer {
        val snapshotMessageDecoder = new TopologySnapshotDecoder
        val snapshotHeaderDecoder = new MessageHeaderDecoder
        val snapshotBuffer = new UnsafeBuffer(new Array[Byte](0))

        private def validate(headerDecoder: MessageHeaderDecoder): Unit = {
            if (headerDecoder.templateId() != snapshotMessageDecoder.sbeTemplateId())
                throw new IOException(
                    s"Invalid message template identifier " +
                    s"${headerDecoder.templateId()}, expected " +
                    s"${snapshotMessageDecoder.sbeTemplateId}")

            if (headerDecoder.schemaId() != snapshotMessageDecoder.sbeSchemaId())
                throw new IOException(
                    s"Invalid schema identifier " +
                    s"${headerDecoder.schemaId()}, expected " +
                    s"${snapshotMessageDecoder.sbeSchemaId()}")

            if (headerDecoder.version() != snapshotMessageDecoder.sbeSchemaVersion())
                throw new IOException(
                    s"Invalid schema version " +
                    s"${headerDecoder.version()}, expected " +
                    s"${snapshotMessageDecoder.sbeSchemaVersion()}")
        }

        private def decodeObjectClass(decoder: ObjectClassDecoder,
                                      snapshot: ObjectSnapshot): Unit = {
            val objects = decoder.`object`()
            val objGroup = new Objects()
            while (objects.hasNext) {
                val obj = objects.next()
                decodeObject(obj, objGroup)
            }
            val clazz = decoder.objectClass()
            val objClass = Class.forName(clazz)
            // NOTE: Decode into a protocol buffer and replace in the map
            // We do this after decoding the raw object because we need
            // the class name, which comes after the data itself.
            val serializer = ObjectMessaging.serializerOf(objClass)
            val entries = objGroup.entrySet().iterator()
            while (entries.hasNext) {
                val entry = entries.next()
                val objProto = serializer.convertTextToMessage(
                    entry.getValue.asInstanceOf[Array[Byte]])
                objGroup.put(entry.getKey, objProto)
            }
            snapshot.putIfAbsent(objClass, objGroup)
        }

        private def decodeObject(decoder: ObjectDecoder,
                                 snapshot: Objects): Unit = {
            val objId = new UUID(decoder.uuid(0), decoder.uuid(1))
            val objData = new Array[Byte](decoder.dataLength())
            decoder.getData(objData, 0, objData.length)
            val data = new String(objData, UTF_8)
            snapshot.putIfAbsent(objId, objData)
        }

        private def decodeStateOwner(decoder: StateOwnerDecoder,
                                     snapshot: StateSnapshot): Unit = {
            val ownerId = new UUID(decoder.uuid(0), decoder.uuid(1))

            val owner = if (decoder.uuid(0) == uuidNullValue &&
                            decoder.uuid(1) == uuidNullValue)
                null
            else
                ownerId.toString

            val stateClassGroup = new StateClasses()
            val classes = decoder.stateClass()
            while (classes.hasNext) {
                classes.next()
                decodeStateClass(classes, stateClassGroup)
            }
            snapshot.putIfAbsent(owner, stateClassGroup)
        }

        private def decodeStateClass(decoder: StateClassDecoder,
                                     snapshot: StateClasses): Unit = {
            val stateIds = decoder.stateId()
            val stateIdGroup = new StateIds()
            while (stateIds.hasNext) {
                val objectId = stateIds.next()
                decodeStateId(objectId, stateIdGroup)
            }
            val clazz = decoder.stateClass()
            snapshot.putIfAbsent(Class.forName(clazz), stateIdGroup)
        }

        private def decodeStateId(decoder: StateIdDecoder,
                                  snapshot: StateIds): Unit = {
            val uuid = new UUID(decoder.uuid(0), decoder.uuid(1))
            val stateKeys = decoder.stateKey()
            val stateKeysGroup = new StateKeys()
            while (stateKeys.hasNext) {
                val stateKey = stateKeys.next()
                decodeStateKey(stateKey, stateKeysGroup)
            }
            snapshot.putIfAbsent(uuid, stateKeysGroup)
        }

        private def decodeStateKey(decoder: StateKeyDecoder,
                                   snapshot: StateKeys): Unit = {
            val stateType = decoder.stateType()

            val multiValueDecoder = decoder.multiValue()
            val multiValue = if (multiValueDecoder.count() > 0) {
                val values = mutable.Set.empty[String]
                while (multiValueDecoder.hasNext) {
                    multiValueDecoder.next()
                    values += multiValueDecoder.multiValueEntry()
                }
                values.toSet
            } else {
                Set.empty[String]
            }

            val stateKey = decoder.key()
            val singleValue = new Array[Byte](decoder.singleValueLength())
            decoder.getSingleValue(singleValue, 0, singleValue.length)

            val stateValue = stateType match {
                case TopologyStateType.SINGLE =>
                    SingleValueKey(stateKey,
                                   Option(
                                       new String(singleValue,
                                                  StateStorage.StringEncoding)),
                                   StateStorage.NoOwnerId)
                case TopologyStateType.MULTI =>
                    MultiValueKey(stateKey,
                                  multiValue)
                case TopologyStateType.NULL_VAL =>
                    SingleValueKey(stateKey,
                                   None,
                                   StateStorage.NoOwnerId)
            }

            snapshot.putIfAbsent(stateKey, stateValue)
        }

        def deserialize(byteArray: Array[Byte]): TopologySnapshot = {
            // decode header
            snapshotBuffer.wrap(byteArray)
            snapshotHeaderDecoder.wrap(snapshotBuffer, 0)

            validate(snapshotHeaderDecoder)

            val offset = snapshotHeaderDecoder.offset() +
                         snapshotHeaderDecoder.encodedLength()
            snapshotMessageDecoder.wrap(snapshotBuffer,
                                        offset,
                                        snapshotHeaderDecoder.blockLength(),
                                        snapshotHeaderDecoder.version())

            val objectSnapshot = new ObjectSnapshot()
            val objectClass = snapshotMessageDecoder.objectClass()
            while (objectClass.hasNext) {
                objectClass.next()
                decodeObjectClass(objectClass, objectSnapshot)
            }

            val stateSnapshot = new StateSnapshot()
            val stateOwner = snapshotMessageDecoder.stateOwner()
            while (stateOwner.hasNext) {
                stateOwner.next()
                decodeStateOwner(stateOwner, stateSnapshot)
            }

            TopologySnapshot(objectSnapshot, stateSnapshot)
        }

    }

}

