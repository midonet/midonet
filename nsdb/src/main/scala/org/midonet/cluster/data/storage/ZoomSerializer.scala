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

package org.midonet.cluster.data.storage

import java.io.StringWriter
import java.nio.charset.Charset

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.protobuf.{Message, TextFormat}

import org.apache.curator.framework.recipes.cache.ChildData

import rx.Notification
import rx.functions.Func1

import org.midonet.cluster.data.Obj
import org.midonet.util.functors.makeFunc1

private[storage] object ZoomSerializer {

    private val JsonFactory = new JsonFactory(new ObjectMapper())
    private val ProtoParser = createProtoParser
    private val Utf8 = Charset.forName("UTF-8")

    private val Deserializers =
        new TrieMap[Class[_], Func1[ChildData, Notification[_]]]

    /**
      * Serializes an object to a byte array for writing to storage.
      */
    @throws[InternalObjectMapperException]
    def serialize(obj: Obj): Array[Byte] = {
        obj match {
            case message: Message => serializeMessage(message)
            case _ => serializeJava(obj)
        }
    }

    /**
      * Deserializes an object from a byte array read from storage.
      */
    @throws[InternalObjectMapperException]
    def deserialize[T](data: Array[Byte], clazz: Class[T]): T = {
        if (classOf[Message].isAssignableFrom(clazz)) {
            deserializeMessage(data, clazz)
        } else {
            deserializeJava(data, clazz)
        }
    }

    /**
      * Returns a cacheable deserializer function for the given type.
      */
    def deserializerOf[T](clazz: Class[T]): Func1[ChildData, Notification[T]] = {
        Deserializers.getOrElseUpdate(clazz, makeFunc1 { data =>
            if (data eq null) {
                Notification.createOnError[T](new NotFoundException(clazz, None))
            } else {
                try Notification.createOnNext[T](deserialize(data.getData, clazz))
                catch {
                    case NonFatal(e) => Notification.createOnError[T](e)
                }
            }
        }).asInstanceOf[Func1[ChildData, Notification[T]]]
    }

    @throws[InternalObjectMapperException]
    private def serializeJava(obj: Obj): Array[Byte] = {
        val writer = new StringWriter()
        try {
            val generator = JsonFactory.createGenerator(writer)
            try generator.writeObject(obj)
            finally generator.close()
            writer.toString.getBytes(Utf8)
        } catch {
            case NonFatal(e) =>
                throw new InternalObjectMapperException(s"Could not serialize $obj", e)
        }
    }

    @throws[InternalObjectMapperException]
    private def deserializeJava[T](data: Array[Byte], clazz: Class[T]): T = {
        try {
            val parser = JsonFactory.createParser(data)
            try parser.readValueAs(clazz)
            finally parser.close()
        } catch {
            case NonFatal(e) =>
                throw new InternalObjectMapperException(
                    s"Could not parse data from ZooKeeper:\n " +
                    s"${new String(data, Utf8)}", e)
        }
    }

    @inline
    private def serializeMessage(message: Message): Array[Byte] = {
        val builder = new java.lang.StringBuilder
        TextFormat.print(message, builder)
        builder.toString.getBytes(Utf8)
    }

    @throws[InternalObjectMapperException]
    private def deserializeMessage[T](data: Array[Byte], clazz: Class[T]): T = {
        try {
            val builder = clazz.getMethod("newBuilder").invoke(null)
                .asInstanceOf[Message.Builder]
            ProtoParser.merge(new String(data, Utf8), builder)
            builder.build().asInstanceOf[T]
        } catch {
            case NonFatal(e) =>
                throw new InternalObjectMapperException(
                    s"Could not parse data from ZooKeeper:\n " +
                    s"${new String(data, Utf8)}", e)
        }
    }

    private def createProtoParser: TextFormat.Parser = {
        val builder = TextFormat.Parser.newBuilder()
        val builderClass = builder.getClass

        // Set the `allowUnknownFields` using reflection: this field is private
        // and not exposed by the builder in the open-source code for Protocol
        // Buffers (according to Google this is intentional).
        val field = builderClass.getDeclaredField("allowUnknownFields")
        field.setAccessible(true)
        field.setBoolean(builder, true)

        builder.build()
    }

}
