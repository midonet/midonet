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

import java.lang.annotation.Annotation
import java.net.URI
import java.util.concurrent.Executors
import java.util.{ConcurrentModificationException, List => JList, Set => JSet}
import javax.validation.{ConstraintViolation, Validator}
import javax.ws.rs._
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core._

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.protobuf.Message
import com.typesafe.scalalogging.Logger
import org.eclipse.jetty.http.HttpStatus
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.data.storage._
import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowGet, AllowList, AllowUpdate}
import org.midonet.cluster.rest_api.models.UriResource
import org.midonet.cluster.rest_api.{BadRequestHttpException, ConflictHttpException, NotFoundHttpException}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.util.reactivex._

object MidonetResource {

    private final val StorageAttempts = 3

    final val Timeout = 5 seconds
    final val OkResponse = Response.ok().build()
    final val OkNoContentResponse = Response.noContent().build()
    final def OkCreated(uri: URI) = Response.created(uri).build()

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
                throw new NotFoundHttpException("Resource not found")
            case e: ObjectReferencedException =>
                throw new WebApplicationException(e, Status.NOT_ACCEPTABLE)
            case e: ReferenceConflictException =>
                throw new ConflictHttpException("Conflicting read")
            case e: ObjectExistsException =>
                throw new WebApplicationException(e, Status.CONFLICT)
        }
    }

    protected def tryWrite[R](f: => Response)(implicit log: Logger): Response = {
        var attempt = 1
        while (attempt <= StorageAttempts) {
            try {
                return f
            } catch {
                case e: NotFoundException =>
                    log.error(s"Write $attempt of $StorageAttempts", e)
                    throw new NotFoundHttpException("Resource not found")
                case e: ObjectReferencedException =>
                    log.error(s"Write $attempt of $StorageAttempts", e)
                    return Response.status(HttpStatus.NOT_ACCEPTABLE_406).build()
                case e: ReferenceConflictException =>
                    log.error(s"Write $attempt of $StorageAttempts", e)
                    throw new ConflictHttpException("Conflicting write")
                case e: ObjectExistsException =>
                    log.error(s"Write $attempt of $StorageAttempts", e)
                    throw new ConflictHttpException("Conflicting write")
                case e: ConcurrentModificationException =>
                    attempt += 1
                case NonFatal(t) =>
                    log.error("Unhandled exception", t)
            }
        }
        Response.status(HttpStatus.CONFLICT_409).build()
    }

    case class ResourceContext @Inject() (backend: MidonetBackend,
                                          uriInfo: UriInfo,
                                          validator: Validator)
}

abstract class MidonetResource[T >: Null <: UriResource]
                              (resContext: ResourceContext)
                              (implicit tag: ClassTag[T]) {

    protected implicit val executionContext =
        ExecutionContext.fromExecutor(Executors.newCachedThreadPool())
    protected final implicit val log =
        Logger(LoggerFactory.getLogger(getClass))

    private val validator = resContext.validator
    private val backend = resContext.backend
    private val uriInfo = resContext.uriInfo

    @GET
    @Path("{id}")
    def get(@PathParam("id") id: String,
            @HeaderParam("Accept") accept: String): T = {
        val produces = getAnnotation(classOf[AllowGet])
        if ((produces eq null) || !produces.value().contains(accept)) {
            log.info(s"Media type {} not acceptable", accept)
            throw new WebApplicationException(Status.NOT_ACCEPTABLE)
        }
        getResource(tag.runtimeClass.asInstanceOf[Class[T]], id)
            .map(getFilter)
            .getOrThrow
    }

    @GET
    def list(@HeaderParam("Accept") accept: String): JList[T] = {
        val produces = getAnnotation(classOf[AllowList])
        if ((produces eq null) || !produces.value().contains(accept)) {
            log.info(s"Media type {} not acceptable", accept)
            throw new WebApplicationException(Status.NOT_ACCEPTABLE)
        }
        listResources(tag.runtimeClass.asInstanceOf[Class[T]])
            .map(_.filter(listFilter).asJava)
            .getOrThrow
    }

    @POST
    def create(t: T, @HeaderParam("Content-Type") contentType: String)
    : Response = {
        val consumes = getAnnotation(classOf[AllowCreate])
        if ((consumes eq null) || !consumes.value().contains(contentType)) {
            log.info(s"Media type {} not supported", contentType)
            throw new WebApplicationException(Status.UNSUPPORTED_MEDIA_TYPE)
        }

        val violations: JSet[ConstraintViolation[T]] = validator.validate(t)
        if (violations.nonEmpty) {
            throw new BadRequestHttpException(violations)
        }

        t.setBaseUri(uriInfo.getBaseUri)

        createFilter(t)
        createResource(t)
    }

    @PUT
    @Path("{id}")
    def update(@PathParam("id") id: String, t: T,
               @HeaderParam("Content-Type") contentType: String): Response = {
        val consumes = getAnnotation(classOf[AllowUpdate])
        if ((consumes eq null) || !consumes.value().contains(contentType)) {
            log.info(s"Media type {} not supported", contentType)
            throw new WebApplicationException(Status.UNSUPPORTED_MEDIA_TYPE)
        }

        getResource(tag.runtimeClass.asInstanceOf[Class[T]], id).map(current => {

            val violations: JSet[ConstraintViolation[T]] = validator.validate(t)
            if (violations.nonEmpty) {
                throw new BadRequestHttpException(violations)
            }

            updateFilter(t, current)
            updateResource(t)
        }).getOrThrow
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: String): Response = {
        deleteResource(tag.runtimeClass.asInstanceOf[Class[T]], id)
    }

    protected implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    protected def getFilter: (T) => T = (t: T) => t

    protected def listFilter: (T) => Boolean = (_) => true

    protected def createFilter: (T) => Unit = (t: T) => { t.create() }

    protected def updateFilter: (T, T) => Unit = (_,_) => { }

    protected def listResources[U >: Null <: UriResource](clazz: Class[U])
    : Future[Seq[U]] = {
        backend.store.getAll(UriResource.getZoomClass(clazz))
            .map(_.map(fromProto(_, clazz)))
    }

    protected def listResources[U >: Null <: UriResource](clazz: Class[U],
                                                          ids: Seq[Any])
    : Future[Seq[U]] = {
        backend.store.getAll(UriResource.getZoomClass(clazz), ids)
                     .map(_.map(fromProto(_, clazz)))
    }

    protected def getResource[U >: Null <: UriResource](clazz: Class[U], id: Any)
    : Future[U] = {
        backend.store.get(UriResource.getZoomClass(clazz), id).map { r =>
            fromProto(r, clazz)
        }
    }

    protected def getResourceState[U >: Null <: UriResource](clazz: Class[U],
                                                             id: Any, key: String)
    : Future[StateKey] = {
        backend.stateStore.getKey(UriResource.getZoomClass(clazz), id, key)
            .asFuture
    }

    protected def createResource[U >: Null <: UriResource](resource: U)
    : Response = {
        val message = toProto(resource)
        log.debug("CREATE: {}\n{}", message.getClass, message)
        tryWrite {
            backend.store.create(message)
            OkCreated(resource.getUri)
        }
    }

    protected def updateResource[U >: Null <: UriResource]
                                (resource: U,
                                 response: Response = OkNoContentResponse)
    : Response = {
        val message = toProto(resource)
        log.debug("UPDATE: {}\n{}", message.getClass, message)
        tryWrite {
            backend.store.update(message)
            response
        }
    }

    protected def deleteResource(clazz: Class[_ <: UriResource], id: Any,
                                 response: Response = OkNoContentResponse)
    : Response = {
        log.debug("DELETE: {}:{}", UriResource.getZoomClass(clazz),
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
                val msg = toProto(resource)
                log.debug("CREATE: {}\n{}", msg.getClass, msg)
                CreateOp(msg)
            case Update(resource) =>
                val msg = toProto(resource)
                log.debug("UPDATE: {}\n{}", msg.getClass, msg)
                UpdateOp(msg)
            case Delete(clazz, id) =>
                log.debug("DELETE: {}:{}", UriResource.getZoomClass(clazz),
                         id.asInstanceOf[AnyRef])
                DeleteOp(UriResource.getZoomClass(clazz), id)
        }
        tryWrite {
            backend.store.multi(zoomOps)
            r
        }
    }

    private def fromProto[U >: Null <: UriResource](message: Message,
                                                    clazz: Class[U]): U = {
        val resource = try {
            ZoomConvert.fromProto(message, clazz)
        } catch {
            case e: ConvertException =>
                log.error("Failed to convert message {} to class {}", message,
                          clazz, e)
                throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
        }
        resource.setBaseUri(uriInfo.getBaseUri)
        resource
    }

    private def toProto[U >: Null <: UriResource](resource: U): Message = {
        try {
            ZoomConvert.toProto(resource, resource.getZoomClass)
        } catch {
            case e: ConvertException =>
                log.error("Failed to convert resource {} to message {}",
                          resource, resource.getZoomClass, e)
                throw new WebApplicationException(Status.INTERNAL_SERVER_ERROR)
        }
    }

    private def getAnnotation[U >: Null <: Annotation](clazz: Class[U]): U = {
        var c: Class[_] = getClass
        while (classOf[MidonetResource[_]].isAssignableFrom(c)) {
            val annotation = c.getAnnotation(clazz)
            if (annotation ne null) {
                return annotation
            }
            c = c.getSuperclass
        }
        null
    }
}
