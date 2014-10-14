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

import com.google.protobuf.Descriptors.{EnumDescriptor, EnumValueDescriptor}
import com.google.protobuf.GeneratedMessage.Builder
import com.google.protobuf.{ByteString, Descriptors, MessageOrBuilder}

/**
 * Converts Java objects to/from Protocol Buffers messages. The class converts
 * all objects fields that are annotated with the <code>ZoomField</code>
 * annotation. The object class may optionally be annotated with the
 * <code>ZoomClass</code> annotation to specify additional conversion
 * options, such as a custom converter or corresponding message class.
 *
 * The Java object class must extend the <code>ZoomObject</code> class, and
 * provide a parameter-less constructor.
 */
object ZoomConvert {
    private val BUILDER_METHOD = "newBuilder"
    private val DESCRIPTOR_METHOD = "getDescriptor"

    private val BYTE = classOf[Byte]
    private val SHORT = classOf[Short]
    private val BYTE_ARRAY = classOf[Array[Byte]]

    private val converters =
        new TrieMap[Class[_ <: Converter[_,_]], Converter[_,_]]()
    converters += classOf[DefaultConverter] -> new DefaultConverter()
    converters += classOf[ObjectConverter] -> new ObjectConverter()

    /**
     * Converts a Java object to a Protocol Buffers message.
     *
     * @param pojo The Java object.
     * @param protoClass The Protocol Buffers message class.
     * @return The Protocol Buffers message.
     */
    def toProto[T <: ZoomObject, U <: MessageOrBuilder](
        pojo: T, protoClass: Class[U]): U = {
        toProtoBuilder(pojo, protoClass).build().asInstanceOf[U]
    }

    def toProtoBuilder[T <: ZoomObject, U <: MessageOrBuilder](
            pojo: T, protoClass: Class[U]): Builder[_] = {
        pojo.beforeToProto()

        to(pojo, newBuilder(protoClass),
           pojo.getClass.asInstanceOf[Class[T]], protoClass)
    }

    /**
     * Converts a Protocol Buffers message to an existing Java object instance.
     *
     * @param pojo The existing Java object.
     * @param proto The Protocol Buffers message.
     */
    def fromProto[T <: ZoomObject, U <: MessageOrBuilder](
            pojo: T, proto: U): Unit = {
        from(proto, pojo, pojo.getClass)
        pojo.afterFromProto()
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
    def fromProto[T >: Null <: ZoomObject, U <: MessageOrBuilder](
            proto: U, pojoClass: Class[T]): T = {
        if (null == proto) {
            return null
        }

        val clazz = newFactory(proto, pojoClass)
        val pojo = clazz.newInstance().asInstanceOf[T]

        from(proto, pojo, clazz)
        pojo.afterFromProto()
        pojo
    }

    /**
     * Internal method to convert a Java object to the corresponding Protocol
     * Buffers message. The method is called recursively to convert the fields
     * from all classes in the object's inheritance hierarchy.
     *
     * @param pojo The Java object.
     * @param builder The Protocol Buffers builder used to convert the object.
     *                If different from <code>null</code> the method uses the
     *                specified builder to create the Protocol Buffers message.
     *                Otherwise, the method creates a new builder according
     *                to the current Protocol Buffers message class.
     * @param pojoClass The Java object class, representing the level in the
     *                  object's inheritance hierarchy at which the conversion
     *                  is performed.
     * @param protoClass The Protocol Buffers class for the output message.
     * @return A Protocol Buffers builder.
     */
    private def to[T <: ZoomObject, U <: MessageOrBuilder](
            pojo: T, builder: Builder[_], pojoClass: Class[_],
            protoClass: Class[U]): Builder[_] = {

        // Get the Protobufs builder for the current class.
        val descriptor = builder.getDescriptorForType

        // Convert all fields to the current Protobufs builder.
        for (pojoField <- pojoClass.getDeclaredFields) {
            val zoomField = pojoField.getAnnotation(classOf[ZoomField])

            if (null != zoomField) {
                val protoField = descriptor.findFieldByName(zoomField.name)

                // Verify the field exists or is optional.
                if (null == protoField) {
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
                        builder.setField(protoField, protoValue)
                    }
                } catch {
                    case e @ (_ : InstantiationException |
                              _ : IllegalAccessException |
                              _ : IllegalArgumentException) =>
                        throw new ConvertException(
                            s"Class $pojoClass failed to convert field "
                            + s"${zoomField.name} from Java type "
                            + s"${pojoField.getType} to Protocol Buffers type "
                            + s"${protoField.getType}", e);
                }
            }
        }

        pojoClass.getSuperclass match {
            case superClass: Class[_]
                if classOf[ZoomObject] != superClass &&
                   classOf[ZoomObject].isAssignableFrom(superClass) =>
                    to(pojo, builder, superClass, protoClass)
            case _ => builder
        }
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
     * @param pojo The output Java object.
     * @param pojoClass The Java object class, representing the level in the
     *                  object's inheritance hierarchy at which the conversion
     *                  is performed.
     */
    private def from[T <: ZoomObject, U <: MessageOrBuilder](
        proto: U, pojo: T, pojoClass: Class[_]): MessageOrBuilder = {


        // Get the Protobufs message to convert the current instance.
        val protoThis = pojoClass.getSuperclass match {
            case superClass: Class[_]
                if classOf[ZoomObject].isAssignableFrom(superClass) =>
                    from(proto, pojo, superClass)
            case _ => proto
        }

        val descriptor = protoThis.getDescriptorForType

        // Convert the fields.
        for (pojoField <- pojoClass.getDeclaredFields) {
            val zoomField = pojoField.getAnnotation(classOf[ZoomField])

            // Ignore the fields without the ZoomField annotation.
            if (null != zoomField) {
                val protoField = descriptor.findFieldByName(zoomField.name)

                // Verify the field exists or is optional.
                if (null == protoField) {
                    throw new ConvertException(
                        s"Message ${descriptor.getName} does not have a " +
                        s"field ${zoomField.name}")
                } else if (protoField.isRepeated ||
                           proto.hasField(protoField)) {
                    // We simply ignore an unset Protobuf field, and the
                    // corresponding POJO field would be set to null or
                    // otherwise an appropriate type-default value.
                    try {
                        val protoValue = protoThis.getField(protoField)
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
            }
        }
        protoThis
    }

    /**
     * Creates a Protocol Buffers message instance for the given class.
     * @param clazz The class for a Protocol Buffers message.
     * @return A Protocol Buffers builder for the given message class.
     */
    private def newBuilder[U <: MessageOrBuilder](clazz: Class[U]):
            Builder[_] = {
        try {
            clazz.getMethod(ZoomConvert.BUILDER_METHOD)
                .invoke(null).asInstanceOf[Builder[_ <: Builder[_ <: AnyRef]]]
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
                new ArrayConverter(
                    getScalarConverter(pojoField.getType.getComponentType,
                                       zoomField))
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[JList[_]]) =>
                val elClass = generic.getActualTypeArguments()(0)
                    .asInstanceOf[Class[_]]
                new ListConverter(getScalarConverter(elClass, zoomField))
            case generic: ParameterizedType
                if generic.getRawType.equals(classOf[Set[_]]) =>
                val elClass = generic.getActualTypeArguments()(0)
                    .asInstanceOf[Class[_]]
                new SetConverter(getScalarConverter(elClass, zoomField))
            case _ => throw new ConvertException(
                s"Unsupported type ${pojoField.getGenericType} for repeated " +
                s"field")
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
     * @param clazz The type..
     * @param zoomField The ZoomField annotation.
     * @return The converter instance.
     */
    private def getScalarConverter(clazz: Class[_],
                                   zoomField: ZoomField): Converter[_,_] = {
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
            case BYTE => pojoValue.asInstanceOf[Byte].toInt
            case SHORT => pojoValue.asInstanceOf[Short].toInt
            case BYTE_ARRAY =>
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
                    protoEnum.getMethod(ZoomConvert.DESCRIPTOR_METHOD)
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
            case BYTE => protoValue.asInstanceOf[Int].toByte
            case SHORT => protoValue.asInstanceOf[Int].toShort
            case BYTE_ARRAY => protoValue.asInstanceOf[ByteString].toByteArray
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
                ZoomConvert.to(value.asInstanceOf[ZoomObject],
                               newBuilder(protoClass), c, protoClass).build()
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
