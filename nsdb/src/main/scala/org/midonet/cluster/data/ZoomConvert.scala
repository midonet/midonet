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
package org.midonet.cluster.data

import java.lang.reflect.{Array => JArray, _}
import java.lang.{Byte => JByte}
import java.util.{ArrayList => JArrayList, HashSet => JHashSet, List => JList, Set => JSet, UUID}

import scala.annotation.meta.{field, param}
import scala.collection.JavaConversions._
import scala.collection.concurrent.TrieMap

import com.google.common.base.Defaults
import com.google.protobuf.Descriptors.{EnumDescriptor, EnumValueDescriptor}
import com.google.protobuf.GeneratedMessage.Builder
import com.google.protobuf.{ByteString, Descriptors, Message}

import org.slf4j.LoggerFactory

import org.midonet.cluster.models.Commons
import org.midonet.cluster.util.UUIDUtil

/**
 * Converts Java objects to/from Protocol Buffers messages. The class converts
 * all objects fields that are annotated with the [[ZoomField]] annotation. The
 * object class may optionally be annotated with the [[ZoomClass]] annotation to
 * specify additional conversion options, such as a custom converter or
 * corresponding message class.
 *
 * The Java object class must extend the [[ZoomObject]] class, and provide a
 * parameter-less constructor.
 */
object ZoomConvert {

    type ScalaZoomField = ZoomField @param @field

    private final val log = LoggerFactory.getLogger(this.getClass)

    private final val BuilderMethod = "newBuilder"
    private final val DescriptorMethod = "getDescriptor"

    private final val ByteClass = classOf[Byte]
    private final val JByteClass = classOf[JByte]
    private final val ShortClass = classOf[Short]
    private final val ByteArrayClass = classOf[Array[Byte]]
    private final val UuidClass = classOf[UUID]

    private type ProtoBuilder = Builder[_ <: Builder[_ <: AnyRef]]

    private case class ConverterKey(clazz: Class[_], converter: Class[_])
    private case class FieldInfo(field: Field, zoomField: ZoomField)
    private case class ClassInfo(clazz: Class[_], zoomClass: ZoomClass,
                                 zoomOneOf: ZoomOneOf,
                                 fieldsInfo: Seq[FieldInfo])

    private val converters =
        new TrieMap[Class[_ <: Converter[_,_]], Converter[_,_]]
    converters += classOf[DefaultConverter] -> new DefaultConverter
    converters += classOf[ObjectConverter] -> new ObjectConverter

    private val arrayConverters = new TrieMap[ConverterKey, ArrayConverter]
    private val listConverters = new TrieMap[ConverterKey, ListConverter]
    private val setConverters = new TrieMap[ConverterKey, SetConverter]
    private val jSetConverters = new TrieMap[ConverterKey, JavaSetConverter]

    private val factories = new TrieMap[Class[_ <: Factory[_,_]], Factory[_,_]]

    /**
     * Converts a Java object to a Protocol Buffers message.
     *
     * @param pojo The Java object.
     * @param protoClass The Protocol Buffers message class.
     * @return The Protocol Buffers message.
     */
    def toProto[T <: ZoomObject, U <: Message]
        (pojo: T, protoClass: Class[U]): U = {
        toProto(pojo, pojo.getClass.asInstanceOf[Class[T]], protoClass)
    }

    /**
      * Converts a Java object to a Protocol Buffers message.
      *
      * @param pojo The Java object.
      * @param pojoClass The Java class for which the fields are converted.
      * @param protoClass The Protocol Buffers message class.
      * @return The Protocol Buffers message.
      */
    def toProto[T <: ZoomObject, U <: Message]
        (pojo: T, pojoClass: Class[T], protoClass: Class[U]): U = {
        pojo.beforeToProto()
        val builder = newBuilder(protoClass)
        to(pojo, pojoClass, builder)
        pojo.afterToProto(builder)
        builder.build().asInstanceOf[U]
    }

    /**
     * Converts a Protocol Buffers message to a Java object. The method creates
     * a new instance of the specified Java class, where the class must have
     * a parameter-less public constructor. The method returns null if the
     * Protocol Buffers message is null.
     *
     * @param proto The Protocol Buffers message.
     * @param pojoClass The Java object class.
     * @return The Java object.
     */
    def fromProto[T >: Null <: ZoomObject, U <: Message]
        (proto: U, pojoClass: Class[T]): T = {
        if (proto eq null) {
            return null
        }
        val clazz = newFactory(proto, pojoClass)
        val defaultConstructor = getDefaultConstructor(clazz)
        if (defaultConstructor ne null) {
            val pojo = clazz.newInstance().asInstanceOf[T]
            from(proto, pojo, pojo.getClass)
            pojo.afterFromProto(proto)
            pojo
        } else {
            val pojo = fromNew(proto, clazz.asInstanceOf[Class[T]])
            pojo.afterFromProto(proto)
            pojo
        }
    }

    /**
      * Converts a Protocol Buffers message to an existing Java object. The
      * method initializes the fields from the given instance, according to the
      * data stored in the message. The method returns null if the Protocol
      * Buffers message is null.
      *
      * @param proto The Protocol Buffers message.
      * @param pojo The Java object.
      * @return The Java object.
      */
    def fromProto[T >: Null <: ZoomObject, U <: Message](proto: U, pojo: T): T = {
        fromProto(proto, pojo, pojo.getClass.asInstanceOf[Class[T]])
    }

    /**
      * Converts a Protocol Buffers message to an existing Java object. The
      * method initializes the fields from the given instance, according to the
      * data stored in the message. The method returns the object unchanged if
      * the Protocol Buffers message is null.
      *
      * @param proto The Protocol Buffers message.
      * @param pojo The Java object.
      * @param pojoClass The Java class for which the fields are converted.
      * @return The Java object.
      */
    def fromProto[T >: Null <: ZoomObject, U <: Message]
        (proto: U, pojo: T, pojoClass: Class[T]): T = {
        if (proto eq null) {
            return pojo
        }
        from(proto, pojo, pojoClass)
        pojo.afterFromProto(proto)
        pojo
    }


    /**
     * Internal method to convert a Java object to the corresponding Protocol
     * Buffers message. The method is called recursively to convert the fields
     * from all classes in the object's inheritance hierarchy.
     *
     * @param pojo The Java object.
     * @param clazz The Java object class, representing the level in the
     *              object's inheritance hierarchy at which the conversion is
     *              performed.
     * @param topBuilder The Protocol Buffers builder for the final message.
     */
    private def to[T <: ZoomObject, U <: Message](
            pojo: T, clazz: Class[_], topBuilder: ProtoBuilder): ProtoBuilder = {

        val zoomClass = clazz.getAnnotation(classOf[ZoomClass])
        // Recursively iterate over all superclasses in the objects inheritance
        // hierarchy, and get the corresponding Protocol Buffers message.
        val superBuilder =
            if (clazz != classOf[ZoomObject] &&
                clazz.getSuperclass != classOf[ZoomObject] &&
                (zoomClass == null || !zoomClass.skipSuper))
                to(pojo, clazz.getSuperclass, topBuilder)
            else topBuilder

        // If the class has a one-of annotation, get the builder from the one-of
        // field.
        val zoomOneOf = clazz.getAnnotation(classOf[ZoomOneOf])
        val thisBuilder = if (zoomOneOf ne null) {
            val superDescriptor = superBuilder.getDescriptorForType
            val oneOfField = superDescriptor.findFieldByName(zoomOneOf.name)
            if (oneOfField eq null) {
                throw new ConvertException(
                    s"Message ${superDescriptor.getName} does not have a " +
                    s"one-of field ${zoomOneOf.name}")
            }
            superBuilder.getFieldBuilder(oneOfField).asInstanceOf[ProtoBuilder]
        } else superBuilder

        // Get the descriptor for the current builder.
        val descriptor = thisBuilder.getDescriptorForType

        for (pojoField <- clazz.getDeclaredFields;
             zoomField = pojoField.getAnnotation(classOf[ZoomField])
             if zoomField ne null) {
            val protoField = descriptor.findFieldByName(zoomField.name)

            // Verify the field exists.
            if (protoField eq null) {
                throw new ConvertException(
                    s"Message ${descriptor.getName} does not have a " +
                    s"field with name ${zoomField.name}")
            }
            try {
                // Get the field value.
                val pojoValue = pojo.getField(pojoField)
                // Ignore the null fields.
                if (null != pojoValue) {
                    val converter = getConverter(pojoField.getType,
                                                 pojoField.getGenericType,
                                                 protoField, zoomField)
                    val protoValue = converter.to(pojoValue,
                                                  pojoField.getGenericType)
                    thisBuilder.setField(protoField, protoValue)
                }
            } catch {
                case e @ (_ : InstantiationException |
                          _ : IllegalAccessException |
                          _ : IllegalArgumentException |
                          _ : ClassCastException) =>
                    val msg = s"Class $clazz failed to convert field " +
                              s"${zoomField.name} from Java type " +
                              s"${pojoField.getType} to Protocol Buffers " +
                              s"type ${protoField.getType}"
                    log.error(msg, e)
                    throw new ConvertException(msg, e)
            }
        }

        thisBuilder
    }

    /**
     * Internal method to convert a Protocol Buffers message to the
     * corresponding Java object. The method is called recursively to convert
     * the fields from all classes in the object's inheritance hierarchy. The
     * method converts these fields from a single or multiple messages,
     * depending on the inheritance policy specified by the [[ZoomClass]]
     * and [[ZoomOneOf]] annotations.
     *
     * @param proto The Protocol Buffers message.
     * @param pojo The Java object class, representing the level in the object's
     *             inheritance hierarchy at which the conversion is performed.
     * @param clazz The Java class corresponding to the current inheritance
     *              level.
     */
    private def from[T <: ZoomObject, U <: Message]
                    (proto: U, pojo: T, clazz: Class[_]): Message = {
        val zoomClass = clazz.getAnnotation(classOf[ZoomClass])

        // Recursively iterate over all superclasses in the objects inheritance
        // hierarchy, and get the corresponding Protocol Buffers message.
        var message =
            if (clazz != classOf[ZoomObject] &&
                clazz.getSuperclass != classOf[ZoomObject] &&
                (zoomClass == null || !zoomClass.skipSuper))
                from(proto, pojo, clazz.getSuperclass)
            else proto

        // Get the descriptor for the current message.
        var descriptor = message.getDescriptorForType

        // If the class has a one-of annotation, extract the message from
        // the one-of field.
        val zoomOneOf = clazz.getAnnotation(classOf[ZoomOneOf])
        if (zoomOneOf ne null) {
            val oneOfField = descriptor.findFieldByName(zoomOneOf.name)
            message = if (oneOfField eq null) {
                throw new ConvertException(
                    s"Message ${descriptor.getName} does not have a " +
                    s"one-of field ${zoomOneOf.name}")
            } else message.getField(oneOfField) match {
                case msg: Message =>
                    descriptor = msg.getDescriptorForType
                    msg
                case _ =>
                    throw new ConvertException(
                        s"Message ${descriptor.getName} one-of field " +
                        s"${zoomOneOf.name} is not a Protocol Buffers " +
                        s"message")
            }
        }

        for (pojoField <- clazz.getDeclaredFields;
             zoomField = pojoField.getAnnotation(classOf[ZoomField])
             if zoomField ne null) {
            val protoField = descriptor.findFieldByName(zoomField.name)

            // Verify the field exists.
            if (protoField eq null) {
                throw new ConvertException(
                    s"Message ${descriptor.getName} does not have a " +
                    s"field ${zoomField.name}")
            } else if (protoField.isRepeated || protoField.hasDefaultValue ||
                       message.hasField(protoField)) {
                // We ignore unset message fields, and let the corresponding
                // Java object field set to the its type-default value.
                try {
                    val protoValue = message.getField(protoField)
                    val converter = getConverter(pojoField.getType,
                                                 pojoField.getGenericType,
                                                 protoField, zoomField)
                    val pojoValue = converter.from(protoValue,
                                                   pojoField.getGenericType)
                    pojo.setField(pojoField, pojoValue)
                } catch {
                    case e @ (_ : InstantiationException |
                              _ : IllegalAccessException |
                              _ : IllegalArgumentException |
                              _ : NullPointerException) =>
                        throw new ConvertException(
                            s"Class ${pojo.getClass} failed to convert " +
                            s"field ${zoomField.name} from Protocol Buffers " +
                            s"type ${protoField.getType} to Java type " +
                            s"${pojoField.getType}", e)
                }
            }
        }

        message
    }

    /**
     * Internal method to convert a Protocol Buffers message to the
     * corresponding Java object using a non-default constructor.
     *
     * @param proto The Protocol Buffers message.
     * @param clazz The Java object class.
     * @return The converted object.
     */
    private def fromNew[T <: ZoomObject, U <: Message]
                       (proto: U, clazz: Class[T]): T = {
        val constructor = getZoomConstructor(clazz)

        // Get the descriptor for the current message.
        val descriptor = proto.getDescriptorForType

        val paramTypes = constructor.getParameterTypes
        val paramGenericTypes = constructor.getGenericParameterTypes
        val paramAnnotations = constructor.getParameterAnnotations
        val params = new Array[Object](paramTypes.length)

        for (index <- params.indices) {
            val zoomField = paramAnnotations(index)
                .find(_.annotationType() == classOf[ZoomField])
                .getOrElse(throw new ConvertException(
                    s"Constructor parameter $index requires a ZoomField " +
                    s"annotation"))
                .asInstanceOf[ZoomField]

            val protoField = descriptor.findFieldByName(zoomField.name)

            // Verify the field exists.
            if (protoField eq null) {
                throw new ConvertException(
                    s"Message ${descriptor.getName} does not have a " +
                    s"field ${zoomField.name}")
            } else if (protoField.isRepeated || protoField.hasDefaultValue ||
                       proto.hasField(protoField)) {
                // We ignore unset message fields, and let the corresponding
                // Java object field set to the its type-default value.
                try {
                    val protoValue = proto.getField(protoField)
                    val converter = getConverter(paramTypes(index),
                                                 paramGenericTypes(index),
                                                 protoField, zoomField)
                    params(index) = converter.from(protoValue,
                                                   paramGenericTypes(index))
                                             .asInstanceOf[AnyRef]
                } catch {
                    case e @ (_ : InstantiationException |
                              _ : IllegalAccessException |
                              _ : IllegalArgumentException |
                              _ : NullPointerException) =>
                        throw new ConvertException(
                            s"Class $clazz failed to convert constructor" +
                            s"parameter ${zoomField.name} from Protocol Buffers " +
                            s"type ${protoField.getType} to Java type " +
                            s"${paramTypes(index)}", e)
                }
            } else {
                params(index) = Defaults.defaultValue(paramTypes(index))
                                        .asInstanceOf[Object]
            }
        }

        constructor.newInstance(params: _*).asInstanceOf[T]
    }

    /**
     * Creates a Protocol Buffers message instance for the given class.
     * @param clazz The class for a Protocol Buffers message.
     * @return A Protocol Buffers builder for the given message class.
     */
    private def newBuilder[U <: Message](clazz: Class[U]): ProtoBuilder = {
        try {
            clazz.getMethod(ZoomConvert.BuilderMethod)
                .invoke(null).asInstanceOf[ProtoBuilder]
        } catch {
            case e @ (_ : NoSuchMethodException |
                      _ : IllegalAccessException |
                      _ : InvocationTargetException) =>
                throw new ConvertException(
                    s"Class $getClass failed to convert: internal error", e);
        }
    }

    /**
     * Creates a Java object factory instance for the specified Protocol Buffers
     * message. The method traverses the object's inheritance hierarchy to
     * determine the top class corresponding to the message.
     * @param proto The Protocol Buffers message.
     * @param clazz The expected class of the Java object. It can be a super
     *              class not necessarily the instance class, allowing the user
     *              to specify abstract classes.
     * @return A factory class.
     */
    private def newFactory[T <: ZoomObject, U <: Message]
                          (proto: U, clazz: Class[_]): Class[_] = {
        var factory = clazz
        val zoomClass = clazz.getAnnotation(classOf[ZoomClass])

        if (null != zoomClass &&
            !zoomClass.factory().equals(classOf[DefaultFactory])) {
            factories.getOrElseUpdate(zoomClass.factory(),
                                      zoomClass.factory().newInstance())
                .asInstanceOf[Factory[_, U]]
                .getType(proto) match {
                case c: Class[_] if clazz != c =>
                    factory = newFactory(proto, c)
                case _ =>
            }
        }
        factory
    }

    /**
     * Returns the default constructor for the given class.
     */
    private def getDefaultConstructor(clazz: Class[_]): Constructor[_] = {
        try {
            val c = clazz.getDeclaredConstructor()
            c.setAccessible(true)
            c
        } catch {
            case ex: NoSuchMethodException => null
        }
    }

    /**
     * Returns the [[Zoom]] constructor for the given class.
     */
    private def getZoomConstructor(clazz: Class[_]): Constructor[_] = {
        clazz.getDeclaredConstructors
             .filter(_.getAnnotation(classOf[Zoom]) != null) match {
            case Array(zc) =>
                zc.setAccessible(true)
                zc
            case arr => throw new ConvertException(
                s"Class ${clazz.getName} must have either a default " +
                "constructor or exactly one constructor annotated with " +
                s"@Zoom but has no default constructor and ${arr.length} " +
                "constructors annotated with @Zoom.")
        }
    }

    /**
     * Gets a converter instance for a field or parameter with the given
     * ZoomField annotation.
     *
     * @param fieldType The field or parameter type.
     * @param genericFieldType The field or parameter generic type.
     * @param protoField The message field descriptor.
     * @param zoomField The field's annotation.
     * @return A converter instance.
     */
    private def getConverter(fieldType: Class[_], genericFieldType: Type,
                             protoField: Descriptors.FieldDescriptor,
                             zoomField: ZoomField): Converter[_,_] = {
        if (!protoField.isRepeated) {
            return getScalarConverter(fieldType, zoomField)
        }

        def getElementClass(`type`: ParameterizedType): Class[_] = {
            `type`.getActualTypeArguments()(0) match {
                case clazz: Class[_] => clazz
                case generic: ParameterizedType =>
                    generic.getRawType.asInstanceOf[Class[_]]
            }
        }

        genericFieldType match {
            case c: Class[_] if c.isArray =>
                getArrayConverter(fieldType.getComponentType, zoomField)
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[JList[_]]) =>
                getListConverter(getElementClass(generic), zoomField)
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[Set[_]]) =>
                getSetConverter(getElementClass(generic), zoomField)
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[JSet[_]]) =>
                getJavaSetConverter(getElementClass(generic), zoomField)
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[Map[_,_]]) =>
                getMapConverter(zoomField)
            case _ => getScalarConverter(fieldType, zoomField)
        }
    }

    /**
     * Gets a converter instance for a given type and ZoomField annotation. If
     * the type class has a ZoomClass annotation, the field annotation takes
     * precedence over the class annotation.
     *
     * The method stores all converters in a converter cache, such that if a
     * converter for a given type already exists, the method does not create
     * a new object.
     *
     * @param clazz The Zoom class.
     * @param zoomField The ZoomField annotation.
     * @return The converter instance.
     */
    private def getScalarConverter(clazz: Class[_], zoomField: ZoomField)
    : Converter[_,_] = {
        val zoomClass = clazz.getAnnotation(classOf[ZoomClass])
        val converter = if (zoomField.converter != classOf[DefaultConverter]) {
            zoomField.converter
        } else if (null != zoomClass) {
            zoomClass.converter
        } else {
            classOf[DefaultConverter]
        }
        converters.getOrElseUpdate(converter, converter.newInstance())
    }

    /** Gets a converter instance for an [[Array]] field. */
    @inline
    private def getArrayConverter(elClass: Class[_], zoomField: ZoomField)
    : ArrayConverter = {
        val elConverter = getScalarConverter(elClass, zoomField)
        arrayConverters.getOrElseUpdate(
            ConverterKey(elClass, elConverter.getClass),
            new ArrayConverter(elConverter))
    }

    /** Gets a converter instance for a [[List]] field. */
    @inline
    private def getListConverter(elClass: Class[_], zoomField: ZoomField)
    : ListConverter = {
        val elConverter = getScalarConverter(elClass, zoomField)
        listConverters.getOrElseUpdate(
            ConverterKey(elClass, elConverter.getClass),
            new ListConverter(elConverter))
    }

    /** Gets a converter instance for a [[Set]] field. */
    @inline
    private def getSetConverter(elClass: Class[_], zoomField: ZoomField)
    : SetConverter = {
        val elConverter = getScalarConverter(elClass, zoomField)
        setConverters.getOrElseUpdate(
            ConverterKey(elClass, elConverter.getClass),
            new SetConverter(elConverter))
    }

    /** Gets a converter instance for a [[JSet]] field. */
    @inline
    private def getJavaSetConverter(elClass: Class[_], zoomField: ZoomField)
    : JavaSetConverter = {
        val elConverter = getScalarConverter(elClass, zoomField)
        jSetConverters.getOrElseUpdate(
            ConverterKey(elClass, elConverter.getClass),
            new JavaSetConverter(elConverter))
    }

    /** Gets a converter instance for a [[Map]] field. */
    @inline
    private def getMapConverter(zoomField: ZoomField): Converter[_,_] = {
        converters.getOrElseUpdate(zoomField.converter,
                                   zoomField.converter.newInstance())
    }

    /**
     * Abstract class for converting values between Java and Protocol Buffers
     * data types.
     */
    abstract class Converter[T <: Any, U <: Any] {
        def toProto(value: T, clazz: Type): U
        def fromProto(value: U, clazz: Type): T

        protected[data] def to(value: Any, clazz: Type)
                              (implicit m: Manifest[T]): Any = {
            if (m.runtimeClass.isAssignableFrom(value.getClass)) {
                toProto(value.asInstanceOf[T], clazz)
            } else {
                throw new ConvertException(
                    s"Value type ${value.getClass} does not match converter " +
                    s"type ${m.runtimeClass}")
            }
        }

        protected[data] def from(value: Any, clazz: Type)
                                (implicit m: Manifest[U]): Any = {
            if (m.runtimeClass.isAssignableFrom(value.getClass)) {
                fromProto(value.asInstanceOf[U], clazz)
            } else {
                throw new ConvertException(
                    s"Value type ${value.getClass} does not match converter " +
                    s"type ${m.runtimeClass}")
            }
        }
    }

    /**
     * A class factory specifies how to create a Java class instance for a
     * Protocol Buffer message.
     */
    trait Factory[T <: ZoomObject, U <: Message] {
        def getType(proto: U): Class[_ <: T]
    }

    /**
     * An exception thrown when a conversion of a Java object to/from Protocol
     * Buffers fails.
     */
    class ConvertException(message: String, cause: Throwable)
        extends RuntimeException(message, cause) {

        def this(message: String) = this(message, null)
    }

    /**
     * The default converter between Java objects and Protocol Buffer messages.
     * The class provides conversion for the following types:
     * - primitive Java types: byte, short, int, long
     * - UUID
     * - enumeration types that are annotated with ZoomEnum and ZoomEnumValue
     * - string
     * - list of primitive types and strings
     */
    protected[data] class DefaultConverter extends Converter[Any, Any] {
        override def toProto(pojoValue: Any, clazz: Type): Any = clazz match {
            case ByteClass => pojoValue.asInstanceOf[Byte].toInt
            case JByteClass => pojoValue.asInstanceOf[JByte].toInt
            case ShortClass => pojoValue.asInstanceOf[Short].toInt
            case ByteArrayClass =>
                ByteString.copyFrom(pojoValue.asInstanceOf[Array[Byte]])
            case UuidClass => UUIDUtil.toProto(pojoValue.asInstanceOf[UUID])
            case enumClass: Class[_] if enumClass.isEnum =>
                val protoEnum =
                    enumClass.getAnnotation(classOf[ZoomEnum]) match {
                        case zoomEnum: ZoomEnum => zoomEnum.clazz
                        case _ => throw new ConvertException(
                            s"Enumeration $clazz requires a ZoomEnum " +
                            s"annotation or a custom converter")
                    }
                val pojoEnumVal = pojoValue.asInstanceOf[Enum[_]]
                val enumValue = enumClass.getField(pojoEnumVal.name) match {
                    case field: Field =>
                        field.getAnnotation(classOf[ZoomEnumValue]) match {
                            case zoomValue: ZoomEnumValue => zoomValue.value
                            case _ => throw new ConvertException(
                                s"Enumeration $clazz field $pojoValue does " +
                                s"not have a ZoomEnumValue annotation")
                        }
                    case _ => throw new ConvertException(
                        s"Enumeration $clazz does not have field $pojoValue")
                }
                try {
                    protoEnum.getMethod(ZoomConvert.DescriptorMethod)
                             .invoke(null)
                             .asInstanceOf[EnumDescriptor]
                             .findValueByName(enumValue)
                } catch {
                    case e @ (_ : NoSuchMethodException |
                              _ : IllegalAccessException |
                              _ : ClassCastException |
                              _ : NullPointerException) =>
                        throw new ConvertException(
                            s"Enumeration $clazz cannot convert field " +
                            s"$pojoValue because the message is not an " +
                            s"enumeration or does not contain the value");
                }
            case _ => pojoValue
        }

        override def fromProto(protoValue: Any, clazz: Type): Any = clazz match {
            case ByteClass => protoValue.asInstanceOf[Int].toByte
            case JByteClass => protoValue.asInstanceOf[Integer].toByte
            case ShortClass => protoValue.asInstanceOf[Int].toShort
            case ByteArrayClass => protoValue.asInstanceOf[ByteString].toByteArray
            case UuidClass => UUIDUtil.fromProto(protoValue.asInstanceOf[Commons.UUID])
            case enumClass: Class[_] if enumClass.isEnum =>
                val protoEnum =
                    enumClass.getAnnotation(classOf[ZoomEnum]) match {
                        case zoomEnum: ZoomEnum => zoomEnum.clazz
                        case _ => throw new ConvertException(
                            s"Enumeration $clazz requires a ZoomEnum " +
                            s"annotation or a custom converter")
                    }
                val protoEnumName = protoValue match {
                    case value: EnumValueDescriptor => value.getName
                    case _ => throw new ConvertException(
                        s"Cannot convert $protoValue to enumeration $clazz " +
                        s"because is not a Protocol Buffers enum value")
                }
                enumClass.getFields.find(field => {
                    field.isEnumConstant &&
                    (field.getAnnotation(classOf[ZoomEnumValue]) match {
                        case zoomValue: ZoomEnumValue =>
                            zoomValue.value.equals(protoEnumName)
                        case _ => throw new ConvertException(
                            s"Enumeration $clazz field $field does not have " +
                            s"a ZoomEnumValue annotation")
                    })
                }) match {
                    case Some(pojoField) => pojoField.get(null)
                    case None => throw new ConvertException(
                        s"Enumeration $clazz does not have a field matching " +
                        s"value $protoValue of Protocol Buffers $protoEnum")
                }
            case _ => protoValue
        }
    }

    /**
     * Converter class for a ZoomObject. All classes that have a ZoomClass
     * annotation receive this converter as default converter.
     */
    protected[data] class ObjectConverter extends Converter[ZoomObject, Message] {

        override def toProto(value: ZoomObject, clazz: Type): Message = clazz match {
            case c: Class[_] if classOf[ZoomObject].isAssignableFrom(c) =>
                val protoClass = c.getAnnotation(classOf[ZoomClass]).clazz()
                val builder = newBuilder(protoClass)
                value.beforeToProto()
                ZoomConvert.to(value.asInstanceOf[ZoomObject], c, builder)
                builder.build()
            case _ => throw new ConvertException(
                s"Object converter not supported for class $clazz");
        }

        override def fromProto(value: Message, clazz: Type): ZoomObject = clazz match {
            case c: Class[_] if classOf[ZoomObject].isAssignableFrom(c) =>
                val obj = ZoomConvert.fromProto(
                    value,
                    c.asInstanceOf[Class[_ >: Null <: ZoomObject]])
                obj.afterFromProto(value)
                obj
            case _ => throw new ConvertException(
                s"Object converter not supported for class $clazz");
        }
    }

    /**
     * Converter class for arrays.
     * @param converter The converter for the array component type.
     */
    protected[data] class ArrayConverter(converter: Converter[_,_])
            extends Converter[Array[_], JList[_]] {

        override def toProto(value: Array[_], clazz: Type): JList[_] = {
            val elClass = clazz.asInstanceOf[Class[_]].getComponentType
            java.util.Arrays.asList(
                value.map(el => converter.to(el, elClass)): _*)
        }

        override def fromProto(value: JList[_], clazz: Type): Array[_] = {
            // The method creates an array using reflection for the
            // specified type. This is necessary to handle arrays of
            // primitive types, where the converter class always returns
            // the boxed equivalents (e.g. Integer[] cannot be cast to int[]
            // and we rely on auto-unboxing to cast the elements)
            val elClass = clazz.asInstanceOf[Class[_]].getComponentType
            val array = JArray.newInstance(elClass, value.size)
                .asInstanceOf[Array[_]]
            Array.copy(
                value.map(el => converter.from(el, elClass)).toArray, 0,
                array, 0, value.size())
            array
        }
    }

    @inline
    private def getElementType(clazz: Type, rawType: Class[_]): Type = clazz match {
        case generic: ParameterizedType
            if generic.getRawType.equals(rawType) =>
            generic.getActualTypeArguments()(0)

        case _ => throw new ConvertException(
            s"Cannot convert between $clazz and protocol buffer repeated type")
    }

    /**
     * Converter class for lists.
     * @param converter The converter for the list component type.
     */
    protected[data] class ListConverter(converter: Converter[_,_])
            extends Converter[JList[_], JList[_]] {

        override def toProto(value: JList[_], clazz: Type): JList[_] = {
            val elType = getElementType(clazz, classOf[JList[_]])
            bufferAsJavaList(value.map(converter.to(_, elType)))
        }

        override def fromProto(value: JList[_], clazz: Type): JList[_] = {
            val elType = getElementType(clazz, classOf[JList[_]])
            new JArrayList(value.map(converter.from(_, elType)))
        }
    }

    /**
     * Converter class for set.
     * @param converter The converter for the list component type.
     */
    protected[data] class SetConverter(converter: Converter[_,_])
        extends Converter[Set[_], JList[_]] {

        override def toProto(value: Set[_], clazz: Type): JList[_] = {
            val elType = getElementType(clazz, classOf[Set[_]])
            value.map(converter.to(_, elType)).toSeq
        }

        override def fromProto(value: JList[_], clazz: Type): Set[_] = {
            val elType = getElementType(clazz, classOf[Set[_]])
            Set(value.map(converter.from(_, elType)).toArray: _*)
        }
    }

    /**
     * Converter class for a Java set.
     * @param converter The converter for the list component type.
     */
    protected[data] class JavaSetConverter(converter: Converter[_,_])
        extends Converter[JSet[_], JList[_]] {

        override def toProto(value: JSet[_], clazz: Type): JList[_] = {
            val elType = getElementType(clazz, classOf[JSet[_]])
            value.map(converter.to(_, elType)).toSeq
        }

        override def fromProto(value: JList[_], clazz: Type): JSet[_] = {
            val elType = getElementType(clazz, classOf[JSet[_]])
            new JHashSet(value.map(converter.from(_, elType)))
        }
    }

    /**
     * The default factory when converting Protocol Buffer messages to Java
     * objects.
     */
    protected[data] class DefaultFactory
            extends Factory[ZoomObject, Message] {
        def getType(proto: Message) = null
    }
}
