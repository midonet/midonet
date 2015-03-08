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
        private val currField = mutable.Stack[FieldDescriptor]()
        private var buildr: Message.Builder = _

        var verb: Verb.Value = _

        /** TODO: make this return a plain Message */
        def builder = buildr

        def suggest(): Seq[String] = buildr match {
            case null => Topology.getDescriptor.getMessageTypes.map{ _.getName }
            case _ if verb == null => Verb.values.map(_.toString).toSeq
            case _ if currField.isEmpty =>
                buildr.getDescriptorForType.getFields.map { _.getName }
            case _ =>
                val f = currField.pop()
                val suggestions = f.getJavaType match {
                    case JavaType.ENUM =>
                        f.getEnumType.getValues.map(_.getName)
                    case _ => Seq.empty
                }
                currField.push(f)
                suggestions
        }

        def parse(token: String): Parser = buildr match {
            case null =>
                try {
                    klass = Class.forName(NAMESPACE + token)
                    buildr = klass.getMethod("newBuilder")
                                  .invoke(null)
                                  .asInstanceOf[Message.Builder]
                    this
                } catch {
                    case e: ClassNotFoundException =>
                        new SyntaxError("Invalid type")
                }
            case _ if verb == null =>
                verb = Verb.fromString(token)
                if (verb == null) { new SyntaxError("Invalid verb") }
                this
            case _ if currField.isEmpty =>
                val desc = buildr.getDescriptorForType
                val f = desc findFieldByName token
                if (f == null) {
                    new SyntaxError("Invalid property name: " + token)
                } else {
                    currField push f
                    this
                }
            case _ =>
                val f = currField.pop()
                buildr.setField(f, cast(token, f))
                this
        }

        private def cast(s: String, f: FieldDescriptor): Any = {
            f.getJavaType match {
                case JavaType.INT => Integer.valueOf(s)
                case JavaType.LONG => java.lang.Long.valueOf(s)
                case JavaType.FLOAT => java.lang.Float.valueOf(s)
                case JavaType.DOUBLE => java.lang.Double.valueOf(s)
                case JavaType.BOOLEAN => java.lang.Boolean.valueOf(s)
                case JavaType.STRING => s
                case JavaType.BYTE_STRING => null
                case JavaType.ENUM => f.getEnumType.findValueByName(s)
                case JavaType.MESSAGE => castMsg(s, f)
            }
        }

        private def castMsg(s: String, f: FieldDescriptor): Any =
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

    class SyntaxError(reason: String) extends Parser {
        override def parse(token: String): Parser = this
        override def builder = throw new IllegalStateException(reason)
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
        val entity = parsed.builder.build()
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
