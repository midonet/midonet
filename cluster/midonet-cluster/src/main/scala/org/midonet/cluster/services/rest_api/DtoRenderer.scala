package org.midonet.cluster.services.rest_api

import java.lang.annotation.Annotation
import java.lang.reflect.Field
import java.util.{List => JList, UUID}

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.Type
import com.google.protobuf.GeneratedMessage.Builder
import com.google.protobuf.Message

import org.midonet.cluster.data.storage.{NotFoundException, ObjectExistsException}
import org.midonet.cluster.data.{ZoomField, ZoomClass, ZoomConvert}
import org.midonet.cluster.models.{Topology, Commons}
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._

object DtoRenderer {

    type DtoClass = Class[_ >: Null <: UriResource]
    type DtoPath = Seq[String]

    private type ZoomBuilder = Builder[_ <: Builder[_ <: AnyRef]]

    /**
     * An attribute containing the following information about a specific DTO
     * resource:
     * - the DTO resource path, containing all parent resources
     * - the DTO class
     * - the [[Resource]] annotation
     * - the ZOOM Protocol Buffers class corresponding to the DTO class
     * - the attribute for the identifier field, if it exists
     * - the attribute for the parent identifier field, if it exists
     * - the attribute for the ownership field, if it exists
     * - a mapping for all fields that correspond to a sub-resource of this
     * resource.
     */
    case class DtoAttribute(path: DtoPath, clazz: DtoClass, resource: Resource,
                            zoom: ZoomClass, id: FieldAttribute,
                            parent: FieldAttribute, ownership: FieldAttribute,
                            subresources: Map[String, FieldAttribute]) {
        /** Sets the identifier for the given DTO, using the DTO value. */
        def setId(dto: UriResource, dtoId: String): Unit = {
            if (id eq null) return
            if (id.field.getType == classOf[UUID])
                parent.field.set(dto, UUID.fromString(dtoId))
            else if (parent.field.getType == classOf[String])
                parent.field.set(dto, dtoId)
        }

        /** Gets the DTO string value corresponding to a Protocol Buffers
          * value object identifier. */
        def getDtoId(protoId: String): String = {
            if (id eq null) return protoId
            val idDescriptor = getFieldDescriptor(id.zoom.name)
            if (idDescriptor.getType == Type.MESSAGE &&
                idDescriptor.getMessageType == Commons.UUID.getDescriptor) {
                val converter = getConverter(id.zoom.converter())
                    .asInstanceOf[ZoomConvert.Converter[Any, Commons.UUID]]
                converter.fromProto(UUIDUtil.toProto(protoId),
                                    classOf[String]).toString
            } else { protoId }
        }

        /** Gets the Protocol Buffers value corresponding to a DTO value object
          * identifier. */
        def getProtoId(dtoId: String): String = {
            if (id eq null) return dtoId
            if (id.zoom eq null) return dtoId
            if (id.field.getType == classOf[UUID]) return dtoId
            val idDescriptor = getFieldDescriptor(id.zoom.name)
            if (idDescriptor.getType == Type.MESSAGE &&
                idDescriptor.getMessageType == Commons.UUID.getDescriptor) {
                val converter = getConverter(id.zoom.converter())
                    .asInstanceOf[ZoomConvert.Converter[Any, Commons.UUID]]
                converter.toProto(dtoId, classOf[String]).asJava.toString
            } else { dtoId }
        }

        /** Generates a new identifier for the given DTO, if the DTO does not
          * have one.*/
        def generateId(dto: UriResource): Unit = {
            if (id eq null) return
            if (id.field.get(dto) == null) {
                val uuid = UUID.randomUUID
                if (id.field.getType == classOf[UUID])
                    id.field.set(dto, uuid)
                else if (id.field.getType == classOf[String])
                    id.field.set(dto, uuid.toString)
            }
        }

        /** Sets the parent identifier for the given DTO. */
        def setParentId(dto: UriResource, parentId: String): Unit = {
            if (parent eq null) return
            if (parentId eq null) return
            if (parent.field.getType == classOf[UUID])
                parent.field.set(dto, UUID.fromString(parentId))
            else if (parent.field.getType == classOf[String])
                parent.field.set(dto, parentId)
        }

        /** Sets the ownership for the given DTO. */
        def setOwners(dto: UriResource, owners: Set[String]): Unit = {
            if (ownership eq null) return
            if (ownership.field.getType == classOf[Boolean])
                ownership.field.set(dto, owners.nonEmpty)
        }

        /** Gets the Protocol Buffers descriptor corresponding to this DTO
          * attribute. */
        def getMessageDescriptor = {
            zoom.clazz
                .getMethod(BuilderMethod).invoke(null).asInstanceOf[ZoomBuilder]
                .getDescriptorForType
        }

        /** Gets the Protocol Buffers descriptor corresponding to this DTO
          * attribute for the given field name. */
        def getFieldDescriptor(name: String) = {
            getMessageDescriptor.findFieldByName(name)
        }

        /** Gets the Protocol Buffers descriptor corresponding to this DTO
          * attribute for the given subresource. */
        def getSubresourceDescriptor(subresource: String) = {
            getMessageDescriptor.findFieldByName(subresources(subresource)
                                                     .zoom.name)
        }

        /** Gets the value of the field annotated with a [[ResourceId]]
          * annotation for the specified Protocol Buffers message. The value,
          * returned as string, represents the message identifier as stored
          * in ZOOM. */
        def getProtoId(message: Message): String = {
            val idDescriptor = getFieldDescriptor(id.zoom.name)
            message.getField(idDescriptor) match {
                case id: Commons.UUID => id.asJava.toString
                case id: String => id
                case any => any.toString
            }
        }

        /** Indicates whether the message has an `id` field. */
        def hasZoomId(message: Message): Boolean = {
            getFieldDescriptor(IdField) ne null
        }

        /** Gets the value of the `id` field for the specified Protocol Buffers
          * message. */
        def getZoomId(message: Message): String = {
            message.getField(getFieldDescriptor(IdField))
                .asInstanceOf[Commons.UUID].asJava.toString
        }

        /** Indicates whether the underlying message for this resource can be
          * saved in storage. This is true when this is either a root resource,
          * or when is referenced by its parent via an UUID list. */
        def isStorable: Boolean = {
            if (path.length == 1) return true
            val parentAttr = ResourceAttributes(path.dropRight(1))
            val parentDesc = parentAttr.getSubresourceDescriptor(resource.name)
            parentDesc.isRepeated &&
            parentDesc.getMessageType == Commons.UUID.getDescriptor
        }
    }

    /**
     * An attribute for a field of a DTO resource, which includes the following
     * information:
     * - the field [[Field]] reflection instance
     * - the [[Subresource]] annotation if this field corresponds to a sub-
     * resource
     * - the [[ZoomField]] annotation if this field corresponds to a Protocol
     * Buffers message field
     */
    case class FieldAttribute(field: Field, subsresource: Subresource,
                              zoom: ZoomField)

    case class ResId(id: String, protoId: String) {
        override def equals(obj: Any): Boolean = obj match {
            case resId: ResId => id == resId.id
            case _ => false
        }
        override def hashCode: Int = id.hashCode
    }

    case class RequestItem(attr: DtoAttribute, id: ResId) {
        def name = attr.resource.name()
        def hasId = id ne null
    }
    case class RequestPath(items: Seq[RequestItem]) {
        def apply(index: Int) = items(index)
        def length = items.length
        def last = items.last

        /**
         * Gets the Protocol Buffers field descriptor for the subresource at
         * the specified `index` in current request path. The `index` must be
         * a positive integer.
         */
        def getSubresourceDescriptor(index: Int): FieldDescriptor = {
            items(index - 1).attr
                .getSubresourceDescriptor(items(index).attr.resource.name())
        }

        /**
         * Indicates whether the subresource from the path at the given index is
         * referenced by its parent resource. The method returns `false` if the
         * resource is a root resource.
         */
        def isSubresourceReferenced(index: Int): Boolean = {
            if (index == 0) return false
            val fieldDescriptor = getSubresourceDescriptor(index)
            fieldDescriptor.isRepeated &&
            fieldDescriptor.getMessageType == Commons.UUID.getDescriptor
        }

        /**
         * Indicates whether the subresource from the path at the given index is
         * embedded into its parent resource. The method returns `false` if the
         * resource is a root resource.
         */
        def isSubresourceEmbedded(index: Int): Boolean = {
            if (index == 0) return false
            val fieldDescriptor = getSubresourceDescriptor(index)
            fieldDescriptor.isRepeated &&
            fieldDescriptor.getMessageType != Commons.UUID.getDescriptor
        }
    }

    /** A rich wrapper class with utility methods for a Protocol Buffers
      * message. */
    final class RichMessage(val msg: Message) extends AnyVal {

        /** Gets the identifier value. */
        def getId: Any = {
            msg.getField(msg.getDescriptorForType.findFieldByName(IdField))
        }

        /** Gets the identifier as a string. */
        def getProtoId: String = {
            getId match {
                case id: Commons.UUID => id.asJava.toString
                case id => id.toString
            }
        }

        /** Gets the subresources for the index at the given request path. */
        def getSubresources(path: RequestPath, index: Int)
        : Seq[Message] = {
            val fieldDescriptor = path.getSubresourceDescriptor(index)
            msg.getField(fieldDescriptor)
                .asInstanceOf[JList[Message]].asScala
        }

        /** Gets the subresources identifiers for the index at the given request
          * path. */
        def getSubresourceIds(path: RequestPath, index: Int): Set[ResId] = {
            val fieldDescriptor = path.getSubresourceDescriptor(index)
            msg.getField(fieldDescriptor)
                .asInstanceOf[JList[Commons.UUID]].asScala
                .map(_.asJava.toString)
                .map(id => ResId(path(index).attr.getDtoId(id), id))
                .toSet
        }

        /**
         * Adds a subresource to the current message for the index at the given
         * request path.
         * @param obj The subresource to add.
         * @return The new message with the subresource added.
         */
        @throws[ObjectExistsException]
        def addSubresource(path: RequestPath, index: Int, obj: Message)
        : Message = {
            val fieldDescriptor = path.getSubresourceDescriptor(index)
            var subresources = msg.getField(fieldDescriptor)
                .asInstanceOf[JList[Message]].asScala
            val protoId = path(index).attr.getProtoId(obj)
            val ids = subresources.map(path(index).attr.getProtoId).toSet
            if (!ids.contains(protoId)) {
                subresources = subresources :+ obj
                val builder = msg.toBuilder
                    .setField(fieldDescriptor, subresources.asJava)

                // TODO: Replace with with subresource operation
                if (obj.getClass == classOf[Topology.TunnelZone.HostToIp]) {
                    assert(msg.getClass == classOf[Topology.TunnelZone])
                    val hostIdsField =
                        msg.getDescriptorForType.findFieldByName("host_ids")
                    var hostIds = msg.getField(hostIdsField)
                        .asInstanceOf[JList[Commons.UUID]].asScala
                        .toSet
                    hostIds = hostIds + UUIDUtil.toProto(protoId)
                    builder.setField(hostIdsField, hostIds.toList.asJava)
                }
                // TODO: End

                builder.build()
            } else throw new ObjectExistsException(path(index).attr.zoom.clazz(),
                                                   protoId)
        }

        /**
         * Adds a subresource identifier to the current message for the index at
         * the given request path.
         * @param id The identifier to add.
         * @return The new message with the subresource identifier added.
         */
        def addSubresourceId(path: RequestPath, index: Int, id: ResId)
        : Message = {
            val fieldDescriptor = path.getSubresourceDescriptor(index)
            var ids = msg.getField(fieldDescriptor)
                .asInstanceOf[JList[Commons.UUID]].asScala.toSet
            val protoId = UUIDUtil.toProto(id.protoId)
            if (!ids.contains(protoId)) {
                ids = ids + protoId
                msg.toBuilder.setField(fieldDescriptor, ids.toList.asJava)
                    .build()
            } else null
        }

        /**
         * Updates a subresource in the current message for the index at the
         * given request path.
         * @param obj The subresource to update.
         * @return The new message with the subresource updated.
         */
        @throws[NotFoundException]
        def updateSubresource(path: RequestPath, index: Int,
                              obj: Message): Message = {
            val fieldDescriptor = path.getSubresourceDescriptor(index)
            var subresources = msg.getField(fieldDescriptor)
                .asInstanceOf[JList[Message]].asScala
            val protoId = path(index).id.protoId
            val ids = subresources.map(path(index).attr.getProtoId).toSet
            if (ids.contains(protoId)) {
                subresources = subresources
                                   .filterNot(path(index).attr.getProtoId(_) == protoId) :+ obj
                msg.toBuilder.setField(fieldDescriptor, subresources.asJava)
                    .build()
            } else throw new NotFoundException(path(index).attr.zoom.clazz(),
                                               protoId)
        }

        /**
         * Deletes a subresource from the current message for the index at the
         * given request path.
         * @param id The subresource identifier.
         * @return The new message with the subresource deleted.
         */
        def deleteSubresource(path: RequestPath, index: Int, id: ResId)
        : Message = {
            val fieldDescriptor = path.getSubresourceDescriptor(index)
            var subresources = msg.getField(fieldDescriptor)
                .asInstanceOf[JList[Message]].asScala
            val ids = subresources.map(path(index).attr.getProtoId).toSet
            if (ids.contains(id.protoId)) {
                subresources = subresources
                    .filterNot(path(index).attr.getProtoId(_) == id.protoId)
                val builder = msg.toBuilder
                    .setField(fieldDescriptor, subresources.asJava)

                // TODO: Replace with with subresource operation
                if (path(index).attr.zoom.clazz ==
                    classOf[Topology.TunnelZone.HostToIp]) {
                    assert(msg.getClass == classOf[Topology.TunnelZone])
                    val hostIdsField =
                        msg.getDescriptorForType.findFieldByName("host_ids")
                    var hostIds = msg.getField(hostIdsField)
                        .asInstanceOf[JList[Commons.UUID]].asScala
                        .toSet
                    hostIds = hostIds - UUIDUtil.toProto(id.protoId)
                    builder.setField(hostIdsField, hostIds.toList.asJava)
                }
                // TODO: End

                builder.build()
            } else null
        }

        /**
         * Deletes a subresource identifier from the current message for the
         * index at the given request path.
         * @param id The subresource identifier.
         * @return The new message with the subresource deleted.
         */
        def deleteSubresourceId(path: RequestPath, index: Int, id: ResId)
        : Message = {
            val fieldDescriptor = path.getSubresourceDescriptor(index)
            var ids = msg.getField(fieldDescriptor)
                .asInstanceOf[JList[Commons.UUID]].asScala.toSet
            val protoId = UUIDUtil.toProto(id.protoId)
            if (ids.contains(protoId)) {
                ids = ids - protoId
                msg.toBuilder.setField(fieldDescriptor, ids.toList.asJava).build()
            } else null
        }

        /** Merges this message with another message, where all fields set
          * in the other message will overwrite the fields from this message. */
        def mergeWith(other: Message): Message = {
            msg.toBuilder.mergeFrom(other).build()
        }

        /** Copies all subresources from the `other` message to the `this`. */
        def copySubresources(path: RequestPath, index: Int, other: Message)
        : Message = {
            val attr = path(index).attr
            val builder = msg.toBuilder
            for (subresource <- attr.subresources.keys) {
                val descriptor = attr.getSubresourceDescriptor(subresource)
                val list = other.getField(descriptor)
                builder.setField(descriptor, list)
            }
            builder.build()
        }

        /** Converts this message to a sequence. */
        def toSeq: Seq[Message] = Seq(msg)
    }

    /** A rich wrapper class with utility methods for a sequence of Protocol
      * Buffer messages. */
    final class RichMessageSeq(val seq: Seq[Message]) extends AnyVal {
        def findByItem(item: RequestItem): Option[Message] = {
            seq.find(item.attr.getProtoId(_) == item.id.protoId)
        }
    }

    private final val BuilderMethod = "newBuilder"
    private final val IdField = "id"

    final val Dtos = Set[DtoClass](
        classOf[Bridge],
        classOf[BridgePort],
        classOf[Chain],
        classOf[DhcpHost],
        classOf[DhcpSubnet],
        classOf[Host],
        classOf[HostInterfacePort],
        classOf[Interface],
        classOf[Port],
        classOf[Route],
        classOf[Router],
        classOf[RouterPort],
        classOf[Rule],
        classOf[TunnelZone],
        classOf[TunnelZoneHost]
    )
    final val ResourceAttributes = getResourceAttributes

    private val converters = new TrieMap[Class[_ <: ZoomConvert.Converter[_,_]],
        ZoomConvert.Converter[_,_]]

    /** Implicit conversion from a Protocol Buffers message to a rich message
      * wrapper. */
    implicit def asRichMessage(msg: Message): RichMessage =
        new RichMessage(msg)

    /** Implicit conversion from a Protocol Buffers message sequence to a rich
      * sequence wrapper. */
    implicit def asRichMessageSeq(seq: Seq[Message]): RichMessageSeq =
        new RichMessageSeq(seq)

    /** Gets a ZOOM converter for the given converter class. The converter
      * instance is cached for future use. */
    private def getConverter(clazz: Class[_ <: ZoomConvert.Converter[_,_]])
    : ZoomConvert.Converter[_,_] = {
        converters.getOrElse(clazz, {
            val c = clazz.newInstance()
                .asInstanceOf[ZoomConvert.Converter[Any, Commons.UUID]]
            converters.putIfAbsent(clazz, c)
            c
        })
    }

    /** Gets a mapping between the DTO path and attribute for all DTO
      * classes. */
    private def getResourceAttributes: Map[DtoPath, DtoAttribute] = {
        val map = new mutable.HashMap[DtoPath, DtoAttribute]
        for (clazz <- Dtos) {
            for (attr <- getDtoAttributes(clazz)) {
                map += attr.path -> attr
            }
        }
        map.toMap
    }

    /** Gets all DTO attributes for a DTO class. The number of attributes for
      * a class equals the number of parents for that class. */
    private def getDtoAttributes(clazz: DtoClass): Seq[DtoAttribute] = {
        val resource = getAnnotation(clazz, classOf[Resource])
        for (path <- getDtoPaths(clazz)) yield
        DtoAttribute(path, clazz, resource,
                     getAnnotation(clazz, classOf[ZoomClass]),
                     getDtoField(clazz, classOf[ResourceId]),
                     getDtoField(clazz, classOf[ParentId]),
                     getDtoField(clazz, classOf[Ownership]),
                     getDtoSubresources(clazz))
    }

    /** Gets all DTO paths for a given DTO class. The number of paths for a
      * class equals the number of parents for that class. */
    private def getDtoPaths(clazz: Class[_]): Seq[DtoPath] = {
        val resource = clazz.getAnnotation(classOf[Resource])
        if (resource eq null) return Seq.empty

        val result = new ArrayBuffer[DtoPath]()
        for (parent <- resource.parents()) {
            val paths = getDtoPaths(parent)
            if (paths.nonEmpty) {
                for (path <- getDtoPaths(parent)) {
                    result += path :+ resource.name()
                }
            } else result += Seq(resource.name())
        }
        result
    }

    /** Gets the field attribute for a given DTO class and field annotation. */
    private def getDtoField(clazz: DtoClass,
                            annotationClass: Class[_ <: Annotation])
    : FieldAttribute = {
        var c: Class[_] = clazz
        while (classOf[UriResource] isAssignableFrom c) {
            for (field <- c.getDeclaredFields) {
                if (field.getAnnotation(annotationClass) ne null) {
                    return FieldAttribute(
                        field, field.getAnnotation(classOf[Subresource]),
                        field.getAnnotation(classOf[ZoomField]))
                }
            }
            c = c.getSuperclass
        }
        null
    }

    /** Gets the mapping between the name of all subresources of a given DTO
      * class and the [[FieldAttribute]] of the field corresponding to each
      * subresource. */
    private def getDtoSubresources(clazz: DtoClass)
    : Map[String, FieldAttribute] = {
        var c: Class[_] = clazz
        val subresources = new mutable.HashMap[String, FieldAttribute]
        while (classOf[UriResource] isAssignableFrom c) {
            for (field <- c.getDeclaredFields) {
                val subresource = field.getAnnotation(classOf[Subresource])
                if (subresource ne null) {
                    subresources += subresource.name -> FieldAttribute(
                        field, subresource,
                        field.getAnnotation(classOf[ZoomField]))
                }
            }
            c = c.getSuperclass
        }
        subresources.toMap
    }

    /** Gets the annotation of the given annotation class for a DTO class. */
    private def getAnnotation[T >: Null <: Annotation]
    (clazz: DtoClass, annotationClass: Class[T]): T = {
        var c: Class[_] = clazz
        while (classOf[UriResource] isAssignableFrom c) {
            val annotation = c.getAnnotation(annotationClass)
            if (annotation != null) return annotation
            else c = c.getSuperclass
        }
        null
    }

}
