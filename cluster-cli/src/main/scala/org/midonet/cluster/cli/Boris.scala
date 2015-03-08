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

package org.midonet.cluster.cli

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Message

import org.midonet.cluster.data.storage.ZookeeperObjectMapper
import org.midonet.cluster.models.Commons.Int32Range
import org.midonet.cluster.models.Topology.Rule.JumpRuleData
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, UUIDUtil}

object Boris extends App {

    object Verb extends Enumeration {
        val CREATE = Value("create")
        val UPDATE = Value("update")
        val DELETE = Value("delete")
        val LIST = Value("list")
        val GET = Value("get")

        def fromString(s: String): Verb.Value = {
            values.find { _.toString.equalsIgnoreCase(s) }.orNull
        }
    }

    class Parser {

        private val NAMESPACE = "org.midonet.cluster.models.Topology$"
        private var klass: Class[_] = _
        private val field = mutable.Stack[FieldDescriptor]()
        private val builder = mutable.Stack[Message.Builder]()

        var verb: Verb.Value = _

        def build = {
            while (builder.size > 1) {
                val b = builder.pop()
                val f = field.pop()
                builder.head.setField(f, b.build())
            }
            builder.pop().build
        }

        /** Provides a list of suggestions according to the current state
          * of the Parser.
          */
        def suggest(): Seq[String] = builder match {
            case null => Topology.getDescriptor.getMessageTypes.map{ _.getName }
            case _ if verb == null => Verb.values.map(_.toString).toSeq
            case _ if field.isEmpty => builder.toList.head.getDescriptorForType
                                                          .getFields
                                                          .map { _ .getName }
            case _ =>
                val f = field.pop()
                val suggestions = f.getJavaType match {
                    case JavaType.ENUM => f.getEnumType.getValues.map(_.getName)
                    case _ => Seq.empty
                }
                field.push(f)
                suggestions
        }

        private def getBuilder(namespace: String, name: String)
        : Message.Builder = {
            klass = Class.forName(namespace + name)
            klass.getMethod("newBuilder").invoke(null)
                                         .asInstanceOf[Message.Builder]
        }

        def parse(token: String): Parser = builder.size match {
            case 0 =>
                // Initial state, we need a type
                try {
                    builder.push(getBuilder(NAMESPACE, token))
                    this
                } catch {
                    case e: ClassNotFoundException =>
                        new SyntaxError(s"Invalid type: $token")
                }
            case _ if verb == null => // Expect a verb
                verb = Verb.fromString(token)
                if (verb == null) {
                    new SyntaxError(s"Invalid verb: $token")
                }
                this
            case _ => parserField(token)
        }

        def parserField(token: String): Parser = {
            val bldr = builder.top
            val f = bldr.getDescriptorForType.findFieldByName(token)
            if (f != null) { // field name is present in the current builder
                field.push(f)
                return this
            }

            if (field.isEmpty) {
                return new SyntaxError(s"Invalid property name: $token")
            }

            val fld = field.top
            if (fld.getJavaType == JavaType.MESSAGE &&
                fld.getContainingOneof != null) {
                // Start a nested complex type, we'll expect a property name
                fld.getMessageType.findFieldByName(token) match {
                    case null =>
                        return new SyntaxError(s"Invalid property name: $token"+
                                               s"for type ${fld .getName}")
                    case nf =>
                        val bldr = builder.top
                        val ns = NAMESPACE +
                                 bldr.getDescriptorForType.getName + "$"
                        try {
                            builder.push (
                                getBuilder(ns, nf.getContainingType.getName)
                            )
                        } catch {
                            case e: ClassNotFoundException =>
                            case e: NoClassDefFoundError =>
                        }
                        field.push(nf)
                }
            } else if (fld.getJavaType != JavaType.MESSAGE) { // A primitive
                field.pop()
                builder.top.setField(fld, fill(fld, token))
            } else {
                // A value inside a complex type
                fillMsg(fld, token) match {
                    case m: Message =>
                        builder.top.setField(field.pop(), m)
                    case b: Message.Builder =>
                        builder.push(b)
                }
            }
            this
        }

        /** Put the given string into a field for the given type descriptor */
        private def fill(f: FieldDescriptor, s: String): Any = {
            f.getJavaType match {
                case JavaType.INT => Integer.valueOf(s)
                case JavaType.LONG => java.lang.Long.valueOf(s)
                case JavaType.FLOAT => java.lang.Float.valueOf(s)
                case JavaType.DOUBLE => java.lang.Double.valueOf(s)
                case JavaType.BOOLEAN => java.lang.Boolean.valueOf(s)
                case JavaType.STRING => s
                case JavaType.BYTE_STRING => null
                case JavaType.ENUM => f.getEnumType.findValueByName(s)
                case JavaType.MESSAGE => fillMsg(f, s)
            }
        }

        /** Put the given string into a field a nested Message type. */
        private def fillMsg(f: FieldDescriptor, s: String): Any = {
            f.getMessageType.getName match {
                case "UUID" => toProto(UUID.fromString(s))
                case "IPAddress" => IPAddressUtil.toProto(s)
                case "IPSubnet" => IPSubnetUtil.toProto(s)
                case "Int32Range" =>
                    val range = s.split(",")
                    Int32Range.newBuilder()
                        .setStart(Integer.valueOf(range(0)))
                        .setEnd(Integer.valueOf(range(1)))
                        .build()
            }
        }

    }

    class SyntaxError(reason: String) extends Parser {
        override def parse(token: String): Parser = this
        override def build = throw new IllegalStateException(reason)
    }

    object ResultCode extends Enumeration {
        val OK = Value(0)
        val FAILURE = Value(1)
    }

    case class Result(entities: Seq[Message], s: String, code: ResultCode.Value)

    def parse(s: String): Parser = {
        s.split(" ").foldLeft(new Parser)((p, token) => p.parse(token))
    }

    def execute(s: String, zoom: ZookeeperObjectMapper): Result = {
        val timeout = Duration(2, TimeUnit.SECONDS)
        val parsed = parse(s)
        val entity = parsed.build
        parsed.verb match {
            case Verb.CREATE =>
                val idField = entity.getDescriptorForType.findFieldByName("id")
                val id = idOf(entity)
                if(Commons.UUID.getDefaultInstance.equals(id)) {
                    val rId = UUID.randomUUID()
                    zoom.create(
                        entity.toBuilder.setField(idField, toProto(rId)).build()
                    )
                    Result(Seq.empty, rId.toString, ResultCode.OK)
                } else {
                    zoom.create(entity)
                    Result(Seq.empty, fromProto(id).toString, ResultCode.OK)
                }
            case Verb.UPDATE =>
                val id = idOf(entity)
                if (Commons.UUID.getDefaultInstance.equals(id)) {
                    throw new IllegalArgumentException("UPDATE impossible, " +
                                                       "missing id field")
                }
                zoom.update(entity)
                Result(Seq.empty, fromProto(id).toString, ResultCode.OK)
            case Verb.DELETE =>
                if (Commons.UUID.getDefaultInstance.equals(idOf(entity))) {
                    throw new IllegalArgumentException("CREATE impossible, " +
                                                       "missing id field")
                }
                zoom.delete(entity.getClass, idOf(entity))
                Result(Seq.empty, "", ResultCode.OK)
            case Verb.GET =>
                if (Commons.UUID.getDefaultInstance.equals(idOf(entity))) {
                    throw new IllegalArgumentException("GET impossible, " +
                                                       "missing id field")
                }
                val e = Await.result(zoom.get(entity.getClass, idOf(entity)),
                                     timeout)
                Result(Seq(e), "", ResultCode.OK)
            case Verb.LIST =>
                val l = Await.result(zoom.getAll(entity.getClass), timeout)
                Result(l, "", ResultCode.OK)
        }
    }

    private def idOf(o: Message): Commons.UUID = {
        val f = o.getDescriptorForType.findFieldByName("id")
        o.getField(f).asInstanceOf[Commons.UUID]
    }
}
