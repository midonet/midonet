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

package org.midonet.cluster.cli.commands

import java.lang.reflect.Field
import java.util
import java.util.UUID

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future, Await}
import scala.concurrent.duration._
import scala.reflect.ClassTag

import com.google.protobuf.MessageOrBuilder

import org.midonet.cluster.cli.ClusterCli
import org.midonet.cluster.cli.commands.Command.Run
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.data.storage.{ObjectReferencedException, ReferenceConflictException, ObjectExistsException, NotFoundException}
import org.midonet.cluster.data.{ZoomConvert, ZoomField, ZoomObject}

object StorageCommand {
    private val StringClass = classOf[String]
    private val IntClass = classOf[Int]
    private val BooleanClass = classOf[Boolean]
    private val UuidClass = classOf[UUID]
}

/**
 * A generic storage command.
 */
abstract class StorageCommand[T >: Null <: ZoomObject, U <: MessageOrBuilder]
    (cli: ClusterCli)(implicit t: ClassTag[T], u: ClassTag[U])
    extends Command {

    import StorageCommand._

    private implicit val executionContext =
        ExecutionContext.fromExecutorService(cli.executor)

    val timeout = cli.config.operationTimeoutMillis millis

    override def run: Run = super.run orElse {
        case args if 0 == args.length => CommandSyntax(help)
        case args if 1 == args.length && args(0).toLowerCase == "list" =>
            list()
        case args if 2 == args.length && args(0).toLowerCase == "get" =>
            get(args(1))
        case args if 4 == args.length && args(0).toLowerCase == "update" =>
            update(args(1), args(2), args(3))
        case args if 2 == args.length && args(0).toLowerCase == "delete" =>
            delete(args(1))
    }

    private def newObject(id: UUID, args: Array[String]): T = {
        newObject(t.runtimeClass.asInstanceOf[Class[_ <: T]], id, args)
    }

    private def newObject(clazz: Class[_ <: T], id: UUID, args: Array[String])
    : T = {
        val obj = clazz.newInstance()

        val fields = getFields(obj.getClass)
        // Set the object identifier.
        fields("id").set(obj, id)
        // Set the object fields.
        for (index <- 0 until args.length by 2) {
            val field = fields(args(index))
            field.set(obj, valueOf(args(index), field, args(index + 1)))
        }

        obj
    }

    private def getFields(clazz: Class[_ <: ZoomObject])
    : mutable.Map[String, Field] = {

        val fields = clazz.getSuperclass match {
            case superClass: Class[_]
                if classOf[ZoomObject].isAssignableFrom(superClass) =>
                getFields(superClass.asInstanceOf[Class[_ <: ZoomObject]])
            case _ => new mutable.HashMap[String, Field]()
        }

        for (field <- clazz.getDeclaredFields) {
            val zoomField = field.getAnnotation(classOf[ZoomField])
            if (null != zoomField) {
                field.setAccessible(true)
                fields += zoomField.name() -> field
                fields += field.getName -> field
            }
        }

        fields
    }

    private def valueOf(name: String, field: Field, value: String): Any = {
        field.getType match {
            case StringClass => value
            case IntClass => Integer.parseInt(value)
            case UuidClass => UUID.fromString(value)
            case BooleanClass if value.toLowerCase == "true" => true
            case BooleanClass if value.toLowerCase == "false" => false
            case _ => new Exception(s"Unsupported field $name for value $value")
        }
    }

    protected def create(id: UUID, args: Array[String]): CommandResult = {
        try {
            val obj = newObject(id, args)
            cli.storage.create(toProto(obj))
            println(s"Create completed: ${asString(obj)}")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    protected def create(clazz: Class[_ <: T], id: UUID, args: Array[String])
    : CommandResult = {
        try {
            val obj = newObject(clazz, id, args)
            cli.storage.create(toProto(obj))
            println(s"Create completed: ${asString(obj)}")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    protected def create(id: UUID, args: Array[String], fn: (T) => Unit)
    : CommandResult = {
        try {
            val obj = newObject(id, args)
            fn(obj)
            cli.storage.create(toProto(obj))
            println(s"Create completed: ${asString(obj)}")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    protected def list(): CommandResult = {
        try {
            val protos = Await.result(
                Future.sequence(
                    Await.result(cli.storage.getAll(u.runtimeClass), timeout)),
                timeout).asInstanceOf[Seq[U]]
            for (proto <- protos) {
                try {
                    println(asString(fromProto(proto)))
                } catch {
                    case e: ConvertException =>
                        println("Failed to convert object {}", proto)
                }
            }
            println(s"List completed: ${protos.size} objects")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    protected def get(id: String): CommandResult = {
        try {
            println(asString(storageGet(UUID.fromString(id))))
            println(s"Get completed: $id")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    protected def update(id: String, fieldName: String, fieldValue: String)
    : CommandResult = {
        try {
            val obj = storageGet(UUID.fromString(id))
            val field = getFields(obj.getClass)(fieldName)
            field.set(obj, valueOf(fieldName, field, fieldValue))
            storageUpdate(obj)
            println(s"Update completed: ${asString(obj)}")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    protected def delete(id: String): CommandResult = {
        try {
            storageDelete(UUID.fromString(id))
            println(s"Delete completed: $id")
            CommandSuccess
        } catch {
            case e: Throwable => CommandFailed(e)
        }
    }

    @throws[ObjectExistsException]
    @throws[ReferenceConflictException]
    protected def storageCreate(obj: T) = {
        cli.storage.create(toProto(obj))
    }

    @throws[NotFoundException]
    @throws[ReferenceConflictException]
    protected def storageUpdate(obj: T) = {
        cli.storage.update(toProto(obj))
    }

    @throws[NotFoundException]
    protected def storageGet(id: UUID): T = {
        val proto = Await.result(cli.storage.get(u.runtimeClass, id), timeout)
            .asInstanceOf[U]
        fromProto(proto)
    }

    @throws[NotFoundException]
    @throws[ObjectReferencedException]
    protected def storageDelete(id: UUID): Unit = {
        cli.storage.delete(u.runtimeClass, id)
    }

    @throws[ConvertException]
    protected def toProto(obj: T): U = {
        ZoomConvert.toProto(obj, u.runtimeClass.asInstanceOf[Class[U]])
    }

    @throws[ConvertException]
    protected def fromProto(proto: U): T = {
        ZoomConvert.fromProto(proto, t.runtimeClass.asInstanceOf[Class[T]])
    }

    protected def asString(obj: T): String =
        s"${obj.getClass.getSimpleName} ${obj.toString}"
}
