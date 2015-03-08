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

import java.util.concurrent.TimeUnit
import java.util.{List => JList, UUID}

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}

import com.google.common.base.Preconditions
import com.google.protobuf.Descriptors.FieldDescriptor
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType.MESSAGE
import com.google.protobuf.Message
import org.apache.curator.framework.CuratorFrameworkFactory
import org.apache.curator.retry.RetryNTimes

import org.midonet.cluster.data.storage.Storage
import org.midonet.cluster.models.Commons.Int32Range
import org.midonet.cluster.models.{Commons, Topology}
import org.midonet.cluster.services.MidonetBackendService
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}

object Boris extends App {

    val backendCfg = new MidonetBackendConfig { // unused actually
        override def isEnabled: Boolean = true
        override def zookeeperMaxRetries: Int = 2
        override def zookeeperRetryMs: Int = 2000
        override def zookeeperRootPath: String = "/midonet"
        override def zookeeperHosts: String = "localhost:2181"
    }
    val retry = new RetryNTimes(backendCfg.zookeeperMaxRetries,
                                backendCfg.zookeeperRetryMs)
    val curator = CuratorFrameworkFactory.newClient("/", retry)
    curator.start()
    curator.blockUntilConnected()

    System.out.println("ZK connected")

    val backend = new MidonetBackendService(backendCfg, curator)
    backend.setupBindings()

    val boris = new Boris(backend.store)

    val cli = new jline.console.ConsoleReader
    cli.setPrompt("boris> ")

    while (true) {
        boris.execute(cli.readLine()) match {
            case Success(r) =>
                System.out.println(s"OK: ${r.s}")
                r.entities.foreach{m => print(m); System.out.println("")}
            case Failure(t) => System.err.println(s"FAIL: ${t.getMessage}")
                t.printStackTrace(System.err)
        }
    }

    def print(m: Message): Unit = {
        val name = m.getDescriptorForType.getName
        m match {
            case id: Commons.UUID =>
                System.out.print(s"$name: ${fromProto(id)} ")
            case ip: Commons.IPAddress =>
                val sIp = IPAddressUtil.toIPAddr(ip)
                System.out.print(s"$name: $sIp ")
            case sn: Commons.IPSubnet =>
                val sSubnet = IPSubnetUtil.fromProto(sn)
                System.out.print(s"$name: $sSubnet ")
            case r: Commons.Int32Range =>
                System.out.print(s"$name: (${r.getStart}, ${r.getEnd}) ")
            case m: Message =>
                System.out.print(s"$name: [ ")
                m.getAllFields.foreach {
                    case (k, v) if k.getType == FieldDescriptor.Type.MESSAGE =>
                        print(v.asInstanceOf[Message])
                    case (k, v) =>
                        System.out.print(s"${k.getName}: $v ")
                }
                System.out.print("] ")
        }
    }
}

/** A simple text interpreter to operate on Protobuf entities against a ZOOM
  * backend. */
class Boris(store: Storage) {

    /** Possible operations allowed on the protobufs. */
    object Verb extends Enumeration {
        val CREATE = Value("create")
        val UPDATE = Value("update")
        val DELETE = Value("delete")
        val LIST = Value("list")
        val GET = Value("get")
        val ADD = Value("add") // add a new value to a repeated field
        val SUB = Value("sub") // remove a new value to a repeated field


        def fromString(s: String): Verb.Value = {
            values.find { _.toString.equalsIgnoreCase(s) }.orNull
        }
    }

    /** A state machine able to parse ZOOM operations on Protobuf models */
    abstract class Parser {

        // Top level namespace. TODO: make parametrizable
        protected[Parser] val NAMESPACE = "org.midonet.cluster.models.Topology$"

        /** Construct a Builder for the class with simple name "name" on the
          * given namespace.
          */
        protected def getBuilder(namespace: String, name: String)
        : Message.Builder = {
            val klass = Class.forName(namespace + name)
            klass.getMethod("newBuilder").invoke(null)
                .asInstanceOf[Message.Builder]
        }
        /** Provides a list of suggestions according to the current state
          * of the Parser.
          *
          * TODO: still experimental.
          */
        def suggestions: Seq[String]
        def parse(token: String): Parser
        def build: Message =
            throw new IllegalStateException("Command incomplete")
    }

    /** Initial state of the parser, expecting a main type */
    class Start extends Parser {
        override def parse(token: String): Parser = {
            try {
                new VerbParser(getBuilder(NAMESPACE, token))
            } catch {
                case e: ClassNotFoundException =>
                    new SyntaxError(s"Invalid type: $token")
                case e: NoClassDefFoundError =>
                    new SyntaxError(s"Invalid type: $token")
            }
        }
        override def suggestions: Seq[String] =
            Topology.getDescriptor.getMessageTypes.map{ _.getName }
    }

    /** Expecting a verb to execute on the main type */
    class VerbParser(mainBuilder: Message.Builder) extends Parser {
        Preconditions.checkNotNull(mainBuilder)
        override def parse(token: String): Parser = {
            Verb.fromString(token) match {
                case null => new SyntaxError(s"Invalid verb: $token")
                case Verb.LIST => new ListParser(mainBuilder)
                case v => new ContentParser(v, mainBuilder)
            }
        }
        override def suggestions: Seq[String] =
            Verb.values.map(_.toString).toSeq
    }

    class ListParser(m: Message.Builder) extends Parser {
        override def suggestions: Seq[String] = Seq.empty
        override def parse(token: String): Parser =
            new SyntaxError(s"LIST doesn't accept any parameters")
        override def build: Message = m.build()
    }

    /** Expecting the content of a message to operate on */
    class ContentParser(val verb: Verb.Value, m: Message.Builder)
        extends Parser {

        private val builders = mutable.Stack[Message.Builder]()
        private val field = mutable.Stack[FieldDescriptor]()

        builders.push(m)

        override def build: Message = {
            while (builders.size > 1) {
                val b = builders.pop()
                val f = field.pop()
                if (verb == Verb.ADD) {
                    builders.head.addRepeatedField(f, b.build())
                } else if (verb == Verb.SUB) {
                    val l = builders.head.getFieldBuilder(f)
                    l.asInstanceOf[JList[_]].remove(b.build())
                } else {
                    builders.head.setField(f, b.build())
                }
            }
            builders.pop().build
        }

        override def suggestions: Seq[String] = {
            if (field.isEmpty) {
                builders.toList.head.getDescriptorForType.getFields
                                                         .map { _.getName }
            } else {
                val f = field.pop()
                val suggestions = f.getJavaType match {
                    case JavaType.ENUM =>
                        f.getEnumType.getValues.map { _.getName }
                    case _ => Seq.empty
                }
                field.push(f)
                suggestions
            }
        }

        private def findFieldInCurrBuilder(token: String) = {
            builders.top.getDescriptorForType.findFieldByName(token)
        }

        private def notPrimitive(fd: FieldDescriptor): Boolean = {
            if (fd.getJavaType == MESSAGE) {
                val typeName = fd.getMessageType.getName
                !List("UUID",
                      "IPAddress",
                      "IPSubnet",
                      "Int32Range").contains(typeName)
            } else {
                false
            }
        }

        /** Consume a token while building the contents of a message */
        override def parse(token: String): Parser = {
            val f = findFieldInCurrBuilder(token)
            if (f != null) {
                // We got a field in the current builder
                field.push(f)
                if (notPrimitive(f)) {
                    // If not a primitive, we also push a Message.Builder for
                    // it to be filled later with property value pairs.
                    try {
                        val b = getBuilder(NAMESPACE, f.getMessageType.getName)
                        builders.push(b)
                    } catch {
                        case ex: Throwable =>
                            return new SyntaxError(s"Invalid type: $token")
                    }
                }
                return this
            }

            if (field.isEmpty) {
                // There is no field in the current builder, nor do we have a
                // field in the stack for which token might be a value.
                //
                // TODO: allow this case only if builder.size > 1, because
                //       this means that the command was filling a nested
                //       type and now should b = builder.pop(), and
                //       builder.top().setField(fld, b.build())
                return new SyntaxError(s"Invalid property name: $token")
            }

            val currField = field.top
            materialize(currField, token) match {
                case null => new SyntaxError(s"Invalid property name: $token")
                case v =>
                    val onField = field.pop()
                    // note that for repeated fields we'll simply store the
                    // element to add/remove, and execute will deal with the
                    // list modification
                    val value = if (onField.isRepeated) List(v).asJava else v
                    builders.top.setField(onField, value)
            }
            this
        }

        /** Put the given string into a field for the given type descriptor,
          * or, if the field is a Message, return the builder.
          *
          * ®return a primitive value, or a value suitable for a Commons type
          *         or a Message.Builder when a complex type that needs further
          *         filling.
          */
        private def materialize(f: FieldDescriptor, s: String): Any = {
            val typeName = if (f.getJavaType == MESSAGE)
                               f.getMessageType.getName else null
            f.getJavaType match {
                case JavaType.INT => Integer.valueOf(s)
                case JavaType.LONG => java.lang.Long.valueOf(s)
                case JavaType.FLOAT => java.lang.Float.valueOf(s)
                case JavaType.DOUBLE => java.lang.Double.valueOf(s)
                case JavaType.BOOLEAN => java.lang.Boolean.valueOf(s)
                case JavaType.STRING => s
                case JavaType.BYTE_STRING => null
                case JavaType.ENUM => f.getEnumType.findValueByName(s)
                case MESSAGE if typeName == "UUID" =>
                    toProto(UUID.fromString(s))
                case MESSAGE if typeName == "IPAddress" =>
                    IPAddressUtil.toProto(s)
                case MESSAGE if typeName == "IPSubnet" =>
                    IPSubnetUtil.toProto(s)
                case MESSAGE if typeName == "Int32Range" =>
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
        override def suggestions = Seq.empty
    }

    object ResultCode extends Enumeration {
        val OK = Value(0)
        val FAILURE = Value(1)
    }

    case class Result(entities: Seq[Message], s: String = "")

    def parse(s: String): Parser = {
        val parser: Parser = new Start
        s.split(" ").foldLeft(parser) { (p, token) => p.parse(token) }
    }

    def execute(s: String): Try[Result] = {
        val timeout = Duration(2, TimeUnit.SECONDS)
        val parsed = parse(s)
        val entity = parsed.build
        Try ( parsed match {
            case p: ContentParser if p.verb == Verb.CREATE =>
                val entityWithId = ensureId(entity)
                val f = entity.getDescriptorForType.findFieldByName("id")
                val id = entityWithId.getField(f).asInstanceOf[Commons.UUID]
                store.create(entityWithId)
                Result(Seq(entityWithId), fromProto(id).toString)
            case p: ContentParser if p.verb == Verb.SUB =>
                val id = assertHasId(entity)
                // Load the entity
                val oldEntity = Await.result(
                    store.get(entity.getClass, fromProto(id).toString), timeout)
                val newBuilder = oldEntity.toBuilder
                entity.getAllFields.find { fv =>
                    // find the repeated field that gets modified in entity
                    fv._1.isRepeated &&
                    !newBuilder.getField(fv._1).asInstanceOf[JList[_]].isEmpty
                } foreach { fv =>
                    // take this field, and update the list of values
                    val remove = entity.getField(fv._1).asInstanceOf[JList[_]]
                    val list = newBuilder.getField(fv._1).asInstanceOf[JList[_]]
                    newBuilder.clearField(fv._1)
                    val newList = list.filterNot { remove.contains }
                    newBuilder.setField(fv._1, newList.asJava)
                }
                val newEntity = newBuilder.build()
                store update newEntity
                Result(Seq(newEntity), fromProto(id).toString)
            case p: ContentParser if p.verb == Verb.UPDATE ||
                                     p.verb == Verb.ADD =>
                val id = assertHasId(entity)
                val e = Await.result(
                    store.get(entity.getClass, fromProto(id).toString), timeout)
                val newEntity = e.toBuilder.mergeFrom(entity).build()
                store update newEntity
                Result(Seq(newEntity), fromProto(id).toString)
            case p: ContentParser if p.verb == Verb.DELETE =>
                val id = assertHasId(entity)
                store.delete(entity.getClass, fromProto(id).toString)
                Result(Seq.empty)
            case p: ContentParser if p.verb == Verb.GET =>
                val id = assertHasId(entity)
                val e = Await.result(
                    store.get(entity.getClass, fromProto(id).toString), timeout)
                Result(Seq(e), fromProto(id).toString)
            case p: ListParser =>
                val l = Await.result(store.getAll(entity.getClass), timeout)
                Result(l, s"${l.size} entities of type ${entity.getClass.getName}")
            case synErr: SyntaxError =>
                synErr.build // will throw
        })
    }

    /** Check that the message has an id with a non default value, or generate
      * a random one.
      */
    private def ensureId[T <: Message](m: T): T = {
        val f = m.getDescriptorForType.findFieldByName("id")
        val id = m.getField(f).asInstanceOf[Commons.UUID]
        if (Commons.UUID.getDefaultInstance.equals(id)) {
            m.toBuilder.setField(f, toProto(UUID.randomUUID())).build()
             .asInstanceOf[T]
        } else {
            m.asInstanceOf[T]
        }
    }

    /** Check that the message has an id field, with a non default value, or
      * throw
      */
    private def assertHasId(m: Message): Commons.UUID = {
        val f = m.getDescriptorForType.findFieldByName("id")
        val id = m.getField(f).asInstanceOf[Commons.UUID]
        if (id == null || Commons.UUID.getDefaultInstance.equals(id)) {
            throw new IllegalArgumentException("Entity doesn't have an id")
        }
        id
    }
}
