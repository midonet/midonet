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
package org.midonet.cluster.data

import java.lang.reflect.{Array => JArray, Field, InvocationTargetException, ParameterizedType, Type}
import java.util
import java.util.{List => JList}

import scala.collection.concurrent.TrieMap
import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import com.google.protobuf.Descriptors.{EnumDescriptor, EnumValueDescriptor}
import com.google.protobuf.GeneratedMessage.Builder
import com.google.protobuf.{ByteString, Descriptors, MessageOrBuilder}

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
    private final val BuilderMethod = "newBuilder"
    private final val DescriptorMethod = "getDescriptor"

    private final val ByteClass = classOf[Byte]
    private final val ShortClass = classOf[Short]
    private final val ByteArrayClass = classOf[Array[Byte]]

    private type ProtoBuilder = Builder[_ <: Builder[_ <: AnyRef]]

    private case class FieldInfo(field: Field, zoomField: ZoomField)
    private case class ClassInfo(clazz: Class[_], zoomClass: ZoomClass,
                                 zoomOneOf: ZoomOneOf,
                                 fieldsInfo: Seq[FieldInfo])
    private case class BuilderInfo(builder: ProtoBuilder, field: String = null)

    private val classes = new TrieMap[Class[_], Seq[ClassInfo]]

    private val converters =
        new TrieMap[Class[_ <: Converter[_,_]], Converter[_,_]]
    converters += classOf[DefaultConverter] -> new DefaultConverter
    converters += classOf[ObjectConverter] -> new ObjectConverter

    private val arrayConverters = new TrieMap[Class[_], ArrayConverter]
    private val listConverters = new TrieMap[Class[_], ListConverter]
    private val setConverters = new TrieMap[Class[_], SetConverter]

    /**
     * Converts a Java object to a Protocol Buffers message.
     *
     * @param pojo The Java object.
     * @param protoClass The Protocol Buffers message class.
     * @return The Protocol Buffers message.
     */
    def toProto[T <: ZoomObject, U <: MessageOrBuilder]
        (pojo: T, protoClass: Class[U]): U = {
        pojo.beforeToProto()
        to(pojo, pojo.getClass.asInstanceOf[Class[T]], protoClass)
    }

    /**
     * Converts a Protocol Buffers message to a Java object. The method creates
     * a new instance of the specified Java class, where the class must have
     * a parameter-less public constructor.
     *
     * @param proto The Protocol Buffers message.
     * @param pojoClass The Java object class.
     * @return The Java object.
     */
    def fromProto[T >: Null <: ZoomObject, U <: MessageOrBuilder]
        (proto: U, pojoClass: Class[T]): T = {
        if (proto eq null) {
            return null
        }

        val pojo: T = from(proto, newFactory(proto, pojoClass))
        pojo.afterFromProto(proto)
        pojo
    }

    /**
     * Returns the classes and the fields from the inheritance hierarchy of the
     * specified class.
     * @param pojoClass The class.
     * @return The fields as a sequence of [[ClassInfo]] instances containing
     *         the information about the classes from the given class
     *         inheritance hierarchy. Each [[ClassInfo]] includes the
     *         [[ZoomClass]] annotation, the [[ZoomOneOf]] annotation and the
     *         sequence of fields annotated with [[ZoomField]].
     */
    private def getClasses(pojoClass: Class[_]): Seq[ClassInfo] = {
        // Get the class information from the cache.
        classes.get(pojoClass) match {
            case Some(seq) => return seq
            case None =>
        }

        // Create a map between inheritance level and fields.
        val fieldsSeq = new ArrayBuffer[ClassInfo]
        var clazz = pojoClass

        do {
            val fields = clazz.getDeclaredFields
            val fieldsList = new ArrayBuffer[FieldInfo](fields.length)
            var fieldIndex = 0
            while ( fieldIndex < fields.length ) {
                val zoomField = fields(fieldIndex).getAnnotation(classOf[ZoomField])
                if (zoomField ne null) {
                    fieldsList += FieldInfo(fields(fieldIndex), zoomField)
                }
                fieldIndex += 1
            }

            val classInfo = ClassInfo(clazz,
                                      clazz.getAnnotation(classOf[ZoomClass]),
                                      clazz.getAnnotation(classOf[ZoomOneOf]),
                                      fieldsList)
            fieldsSeq += classInfo

            clazz = clazz.getSuperclass match {
                case superClass: Class[_]
                    if classOf[ZoomObject] != superClass &&
                       classOf[ZoomObject].isAssignableFrom(superClass) =>
                    superClass
                case _ => null
            }
        } while (clazz ne null)

        classes.putIfAbsent(pojoClass, fieldsSeq)

        fieldsSeq
    }

    /**
     * Internal method to convert a Java object to the corresponding Protocol
     * Buffers message. The method is called recursively to convert the fields
     * from all classes in the object's inheritance hierarchy.
     *
     * @param pojo The Java object.
     * @param pojoClass The Java object class, representing the level in the
     *                  object's inheritance hierarchy at which the conversion
     *                  is performed.
     * @param protoClass The Protocol Buffers class for the output message.
     */
    private def to[T <: ZoomObject, U <: MessageOrBuilder](
            pojo: T, pojoClass: Class[_], protoClass: Class[U]): U = {

        val builders = new mutable.Stack[BuilderInfo]
        builders.push(BuilderInfo(newBuilder(protoClass)))

        // Get the classes information.
        val classes = getClasses(pojoClass)

        // Get the message builder for the current class.
        var descriptor = builders.top.builder.getDescriptorForType

        // Iterate over all levels starting from the top class, and add the
        // fields to the Protocol Buffers message
        var level = classes.length
        while (level > 0) {
            level -= 1
            var index = 0
            // If the class has a one-of annotation, extract the message from
            // the one-of field.
            val zoomOneOf = classes(level).zoomOneOf
            if (zoomOneOf ne null) {
                val oneOfField = descriptor.findFieldByName(zoomOneOf.name)
                if (oneOfField eq null) {
                    throw new ConvertException(
                        s"Message ${descriptor.getName} does not have a " +
                        s"one-of field ${zoomOneOf.name}")
                } else {
                    builders.push(BuilderInfo(
                        builders.top.builder.getFieldBuilder(oneOfField)
                            .asInstanceOf[ProtoBuilder],
                        zoomOneOf.name))
                    descriptor = builders.top.builder.getDescriptorForType
                }
            }

            while (index < classes(level).fieldsInfo.length) {
                val pojoField = classes(level).fieldsInfo(index).field
                val zoomField = classes(level).fieldsInfo(index).zoomField
                val protoField = descriptor.findFieldByName(zoomField.name)

                // Verify the field exists or is optional.
                if (protoField eq null) {
                    throw new ConvertException(
                        s"Message ${descriptor.getName} does not have a " +
                        s"field with name ${zoomField.name}")
                } else try {
                    // Get the field value.
                    val pojoValue = pojo.getField(pojoField)
                    // Ignore the null fields.
                    if (null != pojoValue) {
                        val converter = getConverter(pojoField, protoField,
                                                     zoomField)
                        val protoValue = converter.to(pojoValue,
                                                      pojoField.getGenericType)
                        builders.top.builder.setField(protoField, protoValue)
                    }
                } catch {
                    case e @ (_ : InstantiationException |
                              _ : IllegalAccessException |
                              _ : IllegalArgumentException |
                              _ : ClassCastException) =>
                        throw new ConvertException(
                            s"Class $pojoClass failed to convert field "
                            + s"${zoomField.name} from Java type "
                            + s"${pojoField.getType} to Protocol Buffers type "
                            + s"${protoField.getType}", e);
                }
                index += 1
            }
        }

        // Get the last builder from the builders stack.
        var builderInfo = builders.pop()
        while (builders.nonEmpty) {
            descriptor = builders.top.builder.getDescriptorForType
            val oneOfField = descriptor.findFieldByName(builderInfo.field)
            try {
                builders.top.builder.setField(oneOfField,
                                              builderInfo.builder.build())
            } catch {
                case e @ (_ : InstantiationException |
                          _ : IllegalAccessException |
                          _ : IllegalArgumentException |
                          _ : ClassCastException) =>
                    throw new ConvertException(
                        s"Class $pojoClass failed to convert super class " +
                        s"${classes(level).getClass} to one-of field " +
                        s"${builderInfo.field}")
            }
            builderInfo = builders.pop()
        }

        builderInfo.builder.build().asInstanceOf[U]
    }

    /**
     * Internal method to convert a Protocol Buffers message to the
     * corresponding Java object. The method is called recursively to convert
     * the fields from all classes in the object's inheritance hierarchy. The
     * method converts these fields from a single or multiple messages,
     * depending on the inheritance policy specified by the ZoomClass
     * annotation.
     *
     * @param proto The Protocol Buffers message.
     * @param pojoClass The Java object class, representing the level in the
     *                  object's inheritance hierarchy at which the conversion
     *                  is performed.
     */
    private def from[T <: ZoomObject, U <: MessageOrBuilder](
        proto: U, pojoClass: Class[_]): T = {

        // Get the classes information.
        val classes = getClasses(pojoClass)

        // Create the Java instance.
        val pojo = pojoClass.newInstance().asInstanceOf[T]

        // Get the message and descriptor for the current message.
        var message: MessageOrBuilder = proto
        var descriptor = message.getDescriptorForType

        // Iterate over all levels starting from the top class, and add the
        // fields to the Java instance.
        var level = classes.length
        while (level > 0) {
            level -= 1
            // If the class has a one-of annotation, extract the message from
            // the one-of field.
            val zoomOneOf = classes(level).zoomOneOf
            if (zoomOneOf ne null) {
                val oneOfField = descriptor.findFieldByName(zoomOneOf.name)
                message = if (oneOfField eq null) {
                    throw new ConvertException(
                        s"Message ${descriptor.getName} does not have a " +
                        s"one-of field ${zoomOneOf.name}")
                } else message.getField(oneOfField) match {
                    case msg: MessageOrBuilder =>
                        descriptor = msg.getDescriptorForType
                        msg
                    case _ =>
                        throw new ConvertException(
                            s"Message ${descriptor.getName} one-of field " +
                            s"${zoomOneOf.name} is not a Protocol Buffers " +
                            s"message")
                }
            }

            var index = 0
            while (index < classes(level).fieldsInfo.length) {
                val pojoField = classes(level).fieldsInfo(index).field
                val zoomField = classes(level).fieldsInfo(index).zoomField
                val protoField = descriptor.findFieldByName(zoomField.name)

                // Verify the field exists or is optional.
                if (protoField eq null) {
                    throw new ConvertException(
                        s"Message ${descriptor.getName} does not have a " +
                        s"field ${zoomField.name}")
                } else if (protoField.isRepeated || message.hasField(protoField)) {
                    // We ignore unset message fields, and let the
                    // corresponding POJO field set to the its type-default
                    // value.
                    try {
                        val protoValue = message.getField(protoField)
                        val converter = getConverter(pojoField, protoField,
                                                     zoomField)
                        val pojoValue = converter.from(protoValue,
                                                       pojoField.getGenericType)
                        pojo.setField(pojoField, pojoValue)
                    } catch {
                        case e @ (_ : InstantiationException |
                                  _ : IllegalAccessException |
                                  _ : IllegalArgumentException |
                                  _ : NullPointerException) =>
                            throw new ConvertException(
                                s"Class $pojoClass failed to convert field " +
                                s"${zoomField.name} from Protocol Buffers type " +
                                s"${protoField.getType} to Java type " +
                                s"${pojoField.getType}", e)
                    }
                }
                index += 1
            }
        }

        pojo
    }

    /**
     * Creates a Protocol Buffers message instance for the given class.
     * @param clazz The class for a Protocol Buffers message.
     * @return A Protocol Buffers builder for the given message class.
     */
    private def newBuilder[U <: MessageOrBuilder](clazz: Class[U])
    : ProtoBuilder = {
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
    private def newFactory[U <: MessageOrBuilder](proto: U,
                                                  clazz: Class[_]): Class[_] = {
        var factory = clazz
        val zoomClass = clazz.getAnnotation(classOf[ZoomClass])

        if (null != zoomClass &&
            !zoomClass.factory().equals(classOf[DefaultFactory])) {
            zoomClass.factory().newInstance().asInstanceOf[Factory[_, U]]
                .getType(proto) match {
                case c: Class[_] if clazz != c => factory = newFactory(proto, c)
                case _ =>
            }
        }
        factory
    }

    /**
     * Gets a converter instance for a field with the given ZoomField
     * annotation.
     *
     * @param pojoField The field.
     * @param protoField The message field descriptor.
     * @param zoomField The field's annotation.
     * @return A converter instance.
     */
    private def getConverter(pojoField: Field,
                             protoField: Descriptors.FieldDescriptor,
                             zoomField: ZoomField): Converter[_,_] = {
        if (!protoField.isRepeated) {
            return getScalarConverter(pojoField.getType, zoomField)
        }

        pojoField.getGenericType match {
            case c: Class[_] if c.isArray =>
                getArrayConverter(pojoField.getType.getComponentType, zoomField)
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[JList[_]]) =>
                val elClass = generic.getActualTypeArguments()(0)
                    .asInstanceOf[Class[_]]
                getListConverter(elClass, zoomField)
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[Set[_]]) =>
                val elClass = generic.getActualTypeArguments()(0)
                    .asInstanceOf[Class[_]]
                getSetConverter(elClass, zoomField)
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[Map[_,_]]) =>
                getMapConverter(zoomField)
            case _ => getScalarConverter(pojoField.getType, zoomField)
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
        arrayConverters.getOrElseUpdate(
            elClass,
            new ArrayConverter(getScalarConverter(elClass, zoomField)))
    }

    /** Gets a converter instance for a [[List]] field. */
    @inline
    private def getListConverter(elClass: Class[_], zoomField: ZoomField)
    : ListConverter = {
        listConverters.getOrElseUpdate(
            elClass,
            new ListConverter(getScalarConverter(elClass, zoomField)))
    }

    /** Gets a converter instance for a [[Set]] field. */
    @inline
    private def getSetConverter(elClass: Class[_], zoomField: ZoomField)
    : SetConverter = {
        setConverters.getOrElseUpdate(
            elClass,
            new SetConverter(getScalarConverter(elClass, zoomField)))
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
    trait Factory[T <: ZoomObject, U <: MessageOrBuilder] {
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
     * - enumeration types that are annotated with ZoomEnum and ZoomEnumValue
     * - string
     * - list of primitive types and strings
     */
    protected[data] class DefaultConverter extends Converter[Any, Any] {
        override def toProto(pojoValue: Any, clazz: Type): Any = clazz match {
            case ByteClass => pojoValue.asInstanceOf[Byte].toInt
            case ShortClass => pojoValue.asInstanceOf[Short].toInt
            case ByteArrayClass =>
                ByteString.copyFrom(pojoValue.asInstanceOf[Array[Byte]])
            case enumClass: Class[_] if enumClass.isEnum =>
                val protoEnum =
                    enumClass.getAnnotation(classOf[ZoomEnum]) match {
                        case zoomEnum: ZoomEnum => zoomEnum.clazz
                        case _ => throw new ConvertException(
                            s"Enumeration $clazz requires a ZoomEnum " +
                            s"annotation or a custom converter")
                    }
                val enumValue = enumClass.getField(pojoValue.toString) match {
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
            case ShortClass => protoValue.asInstanceOf[Int].toShort
            case ByteArrayClass => protoValue.asInstanceOf[ByteString].toByteArray
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
    protected[data] class ObjectConverter extends Converter[Any, Any] {

        override def toProto(value: Any, clazz: Type): Any = clazz match {
            case c: Class[_] if classOf[ZoomObject].isAssignableFrom(c) =>
                val protoClass = c.getAnnotation(classOf[ZoomClass]).clazz()
                ZoomConvert.to(value.asInstanceOf[ZoomObject], c, protoClass)
            case _ => throw new ConvertException(
                s"Object converter not supported for class $clazz");
        }

        override def fromProto(value: Any, clazz: Type): Any = clazz match {
            case c: Class[_] if classOf[ZoomObject].isAssignableFrom(c) =>
                ZoomConvert.fromProto(
                    value.asInstanceOf[MessageOrBuilder],
                    c.asInstanceOf[Class[_ >: Null <: ZoomObject]])
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
            util.Arrays.asList(
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

    /**
     * Converter class for lists.
     * @param converter The converter for the list component type.
     */
    protected[data] class ListConverter(converter: Converter[_,_])
            extends Converter[JList[_], JList[_]] {

        override def toProto(value: JList[_], clazz: Type): JList[_] = clazz match {
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[JList[_]]) =>
                val elClass = generic.getActualTypeArguments()(0)
                bufferAsJavaList(value.map(el => converter.to(el, elClass)))
            case _ => throw new ConvertException(
                s"List converter cannot convert $clazz to Protocol Buffers")
        }

        override def fromProto(value: JList[_], clazz: Type): JList[_] = clazz match {
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[JList[_]]) =>
                val elClass = generic.getActualTypeArguments()(0)
                bufferAsJavaList(
                    value.map(el => converter.from(el, elClass)))
            case _ => throw new ConvertException(
                s"List converter cannot convert $clazz to Protocol Buffers")
        }

    }

    /**
     * Converter class for set.
     * @param converter The converter for the list component type.
     */
    protected[data] class SetConverter(converter: Converter[_,_])
            extends Converter[Set[_], JList[_]] {

        override def toProto(value: Set[_], clazz: Type): JList[_] = clazz match {
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[Set[_]]) =>
                val elClass = generic.getActualTypeArguments()(0)
                value.map(el => converter.to(el, elClass)).toSeq
            case _ => throw new ConvertException(
                s"Set converter cannot convert $clazz to Protocol Buffers")
        }

        override def fromProto(value: JList[_], clazz: Type): Set[_] = clazz match {
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[Set[_]]) =>
                val elClass = generic.getActualTypeArguments()(0)
                Set(value.map(el => converter.from(el, elClass)).toArray: _*)
            case _ => throw new ConvertException(
                s"Set converter cannot convert $clazz to Protocol Buffers")
        }
    }

    /**
     * The default factory when converting Protocol Buffer messages to Java
     * objects.
     */
    protected[data] class DefaultFactory
            extends Factory[ZoomObject, MessageOrBuilder] {
        def getType(proto: MessageOrBuilder) = null
    }
}
