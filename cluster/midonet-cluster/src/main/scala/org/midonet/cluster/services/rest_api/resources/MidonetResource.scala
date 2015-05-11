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

package org.midonet.cluster.services.rest_api.resources

import java.util.ConcurrentModificationException
import java.util.concurrent.Executors

import javax.ws.rs._
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import com.google.common.util.concurrent.MoreExecutors._
import com.google.protobuf.Message
import com.typesafe.scalalogging.Logger

import org.eclipse.jetty.http.HttpStatus
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.storage._
import org.midonet.cluster.rest_api.models.UriResource
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

object MidonetResource {

    private final val Timeout = 5 seconds
    private final val StorageAttempts = 3
    private final val OkResponse = Response.ok().build()

    sealed trait Multi
    case class Create[T <: UriResource](resource: T) extends Multi
    case class Update[T <: UriResource](resource: T) extends Multi
    case class Delete(clazz: Class[_ <: UriResource], id: Any) extends Multi


    final class FutureOps[T](val future: Future[T]) extends AnyVal {
        def getOrThrow: T = tryRead {
            Await.result(future, Timeout)
        }
    }

    protected def tryRead[T](f: => T): T = {
        try {
            f
        } catch {
            case e: NotFoundException =>
                throw new WebApplicationException(Status.NOT_FOUND)
            case e: ObjectReferencedException =>
                throw new WebApplicationException(Status.NOT_ACCEPTABLE)
            case e: ReferenceConflictException =>
                throw new WebApplicationException(Status.CONFLICT)
            case e: ObjectExistsException =>
                throw new WebApplicationException(Status.CONFLICT)
        }
    }

    protected def tryWrite[R](f: => Response): Response = {
        var attempt = StorageAttempts
        while (attempt > 0) {
            try {
                return f
            } catch {
                case e: NotFoundException =>
                    return Response.status(HttpStatus.NOT_FOUND_404).build()
                case e: ObjectReferencedException =>
                    return Response.status(HttpStatus.NOT_ACCEPTABLE_406).build()
                case e: ReferenceConflictException =>
                    return Response.status(HttpStatus.CONFLICT_409).build()
                case e: ObjectExistsException =>
                    return Response.status(HttpStatus.CONFLICT_409).build()
                case e: ConcurrentModificationException =>
                    attempt -= 1
            }
        }
        Response.status(HttpStatus.CONFLICT_409).build()
    }

}

abstract class MidonetResource(backend: MidonetBackend, uriInfo: UriInfo) {

    protected implicit val executionContext =
        ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    protected final val log =
        Logger(LoggerFactory.getLogger(classOf[MidonetResource]))

    protected implicit def toFutureOps[T](future: Future[T]): FutureOps[T] = {
        new FutureOps(future)
    }

    protected def listResources[T >: Null <: UriResource](clazz: Class[T])
    : Future[Seq[T]] = {
        backend.store.getAll(UriResource.getZoomClass(clazz))
            .map(_.map(fromProto(_, clazz)))
    }

    protected def listResources[T >: Null <: UriResource](clazz: Class[T],
                                                          ids: Seq[Any])
    : Future[Seq[T]] = {
        backend.store.getAll(UriResource.getZoomClass(clazz), ids)
            .map(_.map(fromProto(_, clazz)))
    }

    protected def getResource[T >: Null <: UriResource](clazz: Class[T], id: Any)
    : Future[T] = {
        backend.store.get(UriResource.getZoomClass(clazz), id)
            .map(fromProto(_, clazz))
    }

    protected def getResourceOwners[T >: Null <: UriResource](clazz: Class[T],
                                                              id: Any)
    : Future[Set[String]] = {
        backend.ownershipStore.getOwners(UriResource.getZoomClass(clazz), id)
    }

    protected def createResource[T >: Null <: UriResource](resource: T)
    : Response = {
        resource.setBaseUri(uriInfo.getBaseUri)
        val message = ZoomConvert.toProto(resource, resource.getZoomClass)
        log.info("CREATE: {}\n{}", message.getClass, message)
        tryWrite {
            backend.store.create(message)
            Response.created(resource.getUri).build()
        }
    }

    protected def updateResource[T >: Null <: UriResource]
                                (resource: T, response: Response = OkResponse)
    : Response = {
        val message = ZoomConvert.toProto(resource, resource.getZoomClass)
        log.info("UPDATE: {}\n{}", message.getClass, message)
        tryWrite {
            backend.store.update(message)
            response
        }
    }

    protected def deleteResource(clazz: Class[_ <: UriResource], id: Any,
                                 response: Response = OkResponse)
    : Response = {
        log.info("DELETE: {}:{}", UriResource.getZoomClass(clazz),
                 id.asInstanceOf[AnyRef])
        tryWrite {
            backend.store.delete(UriResource.getZoomClass(clazz), id)
            response
        }
    }

    protected def multiResource(ops: Seq[Multi], r: Response = OkResponse)
    : Response = {
        val zoomOps = ops.map {
            case Create(resource) =>
                val msg = ZoomConvert.toProto(resource, resource.getZoomClass)
                log.info("CREATE: {}\n{}", msg.getClass, msg)
                CreateOp(msg)
            case Update(resource) =>
                val msg = ZoomConvert.toProto(resource, resource.getZoomClass)
                log.info("UPDATE: {}\n{}", msg.getClass, msg)
                UpdateOp(msg)
            case Delete(clazz, id) =>
                log.info("DELETE: {}:{}", UriResource.getZoomClass(clazz),
                         id.asInstanceOf[AnyRef])
                DeleteOp(UriResource.getZoomClass(clazz), id)
        }
        tryWrite {
            backend.store.multi(zoomOps)
            r
        }
    }

    private def fromProto[T >: Null <: UriResource](message: Message,
                                                    clazz: Class[T]): T = {
        val resource = ZoomConvert.fromProto(message, clazz)
        resource.setBaseUri(uriInfo.getBaseUri)
        resource
    }
}
