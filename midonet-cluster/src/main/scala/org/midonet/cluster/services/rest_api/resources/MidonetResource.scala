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
import java.util.concurrent.Executors.newCachedThreadPool
import java.util.{ConcurrentModificationException, List => JList, Set => JSet}
import javax.validation.{ConstraintViolation, Validator}
import javax.ws.rs._
import javax.ws.rs.core.Response.Status._
import javax.ws.rs.core._

import scala.collection.JavaConverters._
import scala.concurrent._
import scala.concurrent.duration._
import scala.reflect.ClassTag
import scala.util.control.NonFatal

import com.google.inject.Inject
import com.google.protobuf.Message
import com.lmax.disruptor.util.DaemonThreadFactory
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import org.slf4j.LoggerFactory.getLogger

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.data.storage._
import org.midonet.cluster.rest_api.ResponseUtils.buildErrorResponse
import org.midonet.cluster.rest_api._
import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowGet, AllowList, AllowUpdate}
import org.midonet.cluster.rest_api.models.UriResource
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.util.logging.ProtoTextPrettifier.makeReadable
import org.midonet.cluster.{restApiLog, restApiResourceLog}
import org.midonet.midolman.state._
import org.midonet.util.reactivex._

object MidonetResource {

    private final val log = getLogger(restApiLog)
    private final val StorageAttempts = 3

    type Ids = Future[Seq[Any]]
    type Ops = Future[Seq[Multi]]
    final val NoOps = Future.successful(Seq.empty[Multi])

    final val Timeout = 30 seconds
    final val OkResponse = Response.ok().build()
    final val OkNoContentResponse = Response.noContent().build()
    final def OkCreated(uri: URI) = Response.created(uri).build()

    sealed trait Multi
    case class Create[T <: UriResource](resource: T) extends Multi
    case class Update[T <: UriResource](resource: T) extends Multi
    case class Delete(clazz: Class[_ <: UriResource], id: Any) extends Multi
    case class CreateNode(path: String) extends Multi

    final val DefaultHandler: PartialFunction[Response, Response] = {
        case r => r
    }
    final val DefaultCatcher: PartialFunction[Throwable, Response] = {
        case e: WebApplicationException => e.getResponse
    }

    final class FutureOps[T](val future: Future[T]) extends AnyVal {
        def getOrThrow: T = tryRead {
            Await.result(future, Timeout)
        }
    }

    protected[resources] def tryRead[T](f: => T): T = {
        try {
            f
        } catch {
            case e: NotFoundException =>
                throw new NotFoundHttpException(e.getMessage)
            case e: ObjectReferencedException =>
                throw new WebApplicationException(e, NOT_ACCEPTABLE)
            case e: ReferenceConflictException =>
                throw new ConflictHttpException(e.getMessage)
            case e: ObjectExistsException =>
                throw new ConflictHttpException(e.getMessage)
            case e: TimeoutException =>
                log.warn("Timeout: ", e)
                throw new ServiceUnavailableHttpException("Timeout")
        }
    }

    protected[resources] def tryWrite[R](f: => Response)(implicit log: Logger)
    : Response = {
        var attempt = 1
        while (attempt <= StorageAttempts) {
            try {
                return f
            } catch {
                case e: NotFoundException =>
                    log.warn(e.getMessage)
                    return buildErrorResponse(NOT_FOUND, e.getMessage)
                case e: ObjectReferencedException =>
                    log.warn(e.getMessage)
                    return buildErrorResponse(CONFLICT, e.getMessage)
                case e: ReferenceConflictException =>
                    log.warn(e.getMessage)
                    return buildErrorResponse(CONFLICT, e.getMessage)
                case e: ObjectExistsException =>
                    log.warn(e.getMessage)
                    return buildErrorResponse(CONFLICT, e.getMessage)
                case e: ConcurrentModificationException =>
                    log.error(s"Write $attempt of $StorageAttempts failed " +
                              "due to a concurrent modification: retrying", e)
                    attempt += 1
                case NonFatal(e) =>
                    log.error("Unhandled exception", e)
                    return buildErrorResponse(INTERNAL_SERVER_ERROR,
                                              e.getMessage)
            }
        }
        Response.status(CONFLICT).build()
    }

    protected[resources] def tryLegacyRead[T](f: => T): T = {
        try {
            f
        } catch {
            case e: NoStatePathException =>
                throw new NotFoundHttpException("Resource not found")
        }
    }

    protected[resources] def tryLegacyWrite(f: => Response)(implicit log: Logger)
    : Response = {
        try {
            f
        } catch {
            case e: NoStatePathException =>
                buildErrorResponse(NOT_FOUND, "Resource not found")
            case e: NodeNotEmptyStateException =>
                buildErrorResponse(CONFLICT, "Conflicting write")
            case e: StatePathExistsException =>
                buildErrorResponse(CONFLICT, "Conflicting write")
            case e: StateVersionException =>
                buildErrorResponse(CONFLICT, "Conflicting write")
        }
    }

    protected def tryResponse(handler: PartialFunction[Response, Response],
                              catcher: PartialFunction[Throwable, Response])
                             (f: => Response): Response = {
        (handler orElse DefaultHandler)(try f catch catcher orElse DefaultCatcher)
    }

    case class ResourceContext @Inject() (backend: MidonetBackend,
                                          uriInfo: UriInfo,
                                          validator: Validator,
                                          seqDispenser: SequenceDispenser,
                                          stateTables: StateTableStorage)

}

abstract class MidonetResource[T >: Null <: UriResource]
                              (resContext: ResourceContext)
                              (implicit tag: ClassTag[T]) {

    protected implicit val executionContext =
        ExecutionContext.fromExecutor(
            newCachedThreadPool(DaemonThreadFactory.INSTANCE))
    protected final implicit val log =
        Logger(LoggerFactory.getLogger(restApiResourceLog(getClass)))

    private val validator = resContext.validator
    protected val backend = resContext.backend
    protected val uriInfo = resContext.uriInfo

    @GET
    @Path("{id}")
    def get(@PathParam("id") id: String,
            @HeaderParam("Accept") accept: String): T = {
        val produces = getAnnotation(classOf[AllowGet])
        if ((produces eq null) || !produces.value().contains(accept)) {
            log.info("Media type {} not acceptable", accept)
            throw new WebApplicationException(NOT_ACCEPTABLE)
        }
        getResource(tag.runtimeClass.asInstanceOf[Class[T]], id)
            .flatMap(getFilter)
            .getOrThrow
    }

    @GET
    def list(@HeaderParam("Accept") accept: String): JList[T] = {
        val produces = getAnnotation(classOf[AllowList])
        if ((produces eq null) || !produces.value().contains(accept)) {
            log.info("Media type {} not acceptable", accept)
            throw new WebApplicationException(NOT_ACCEPTABLE)
        }
        val list = listIds flatMap { ids =>
            if (ids eq null) {
                listResources(tag.runtimeClass.asInstanceOf[Class[T]])
            } else {
                listResources(tag.runtimeClass.asInstanceOf[Class[T]], ids)
            }
        } flatMap { list =>
            listFilter(list)
        } getOrThrow

        list.asJava
    }

    @POST
    def create(t: T, @HeaderParam("Content-Type") contentType: String)
    : Response = {
        val consumes = getAnnotation(classOf[AllowCreate])
        if ((consumes eq null) || !consumes.value().contains(contentType)) {
            log.info("Media type {} not supported", contentType)
            throw new WebApplicationException(UNSUPPORTED_MEDIA_TYPE)
        }

        t.setBaseUri(uriInfo.getBaseUri)

        tryResponse(handleCreate, catchCreate) {
            createFilter(t) map { ops =>
                throwIfViolationsOn(t)
                multiResource(Seq(Create(t)) ++ ops, OkCreated(t.getUri))
            } getOrThrow
        }
    }

    @PUT
    @Path("{id}")
    def update(@PathParam("id") id: String, t: T,
               @HeaderParam("Content-Type") contentType: String): Response = {
        val consumes = getAnnotation(classOf[AllowUpdate])
        if ((consumes eq null) || !consumes.value().contains(contentType)) {
            log.info("Media type {} not supported", contentType)
            throw new WebApplicationException(UNSUPPORTED_MEDIA_TYPE)
        }

        val clazz = tag.runtimeClass.asInstanceOf[Class[T]]
        tryResponse(handleUpdate, catchUpdate) {
            getResource(clazz, id) flatMap { current =>
                throwIfViolationsOn(t)
                updateFilter(t, current)
            } map { ops =>
                multiResource(ops :+ Update(t), OkNoContentResponse)
            } getOrThrow
        }
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: String): Response = {
        val clazz = tag.runtimeClass.asInstanceOf[Class[T]]
        tryResponse(handleDelete, catchDelete) {
            deleteFilter(id) map { ops =>
                multiResource(ops :+ Delete(clazz, id), OkNoContentResponse)
            } getOrThrow
        }
    }

    protected def throwIfViolationsOn[U](t: U): Unit = {
        val violations: JSet[ConstraintViolation[U]] = validator.validate(t)
        if (!violations.isEmpty) {
            throw new BadRequestHttpException(violations)
        }
    }

    protected implicit def toFutureOps[U](future: Future[U]): FutureOps[U] = {
        new FutureOps(future)
    }

    protected def getFilter(t: T): Future[T] = Future.successful(t)

    protected def listIds: Ids = Future.successful(null)

    protected def listFilter(list: Seq[T]): Future[Seq[T]] =
        Future.successful(list)

    protected def createFilter(t: T): Ops = { t.create(); NoOps }

    protected def updateFilter(to: T, from: T): Ops = { NoOps }

    protected def deleteFilter(id: String): Ops = { NoOps }

    protected def handleCreate: PartialFunction[Response, Response] =
        DefaultHandler

    protected def handleUpdate: PartialFunction[Response, Response] =
        DefaultHandler

    protected def handleDelete: PartialFunction[Response, Response] =
        DefaultHandler

    protected def catchCreate: PartialFunction[Throwable, Response] =
        DefaultCatcher

    protected def catchUpdate: PartialFunction[Throwable, Response] =
        DefaultCatcher

    protected def catchDelete: PartialFunction[Throwable, Response] =
        DefaultCatcher

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

    protected def getResources[U >: Null <: UriResource](clazz: Class[U], ids: Seq[Any])
    : Future[Seq[U]] = {
        backend.store.getAll(UriResource.getZoomClass(clazz), ids).map { r =>
            r.map(fromProto(_, clazz))
        }
    }

    protected def getResourceState[U >: Null <: UriResource](host: String,
                                                             clazz: Class[U],
                                                             id: Any, key: String)
    : Future[StateKey] = {
        backend.stateStore.getKey(host, UriResource.getZoomClass(clazz), id, key)
            .asFuture
    }

    protected def hasResource[U >: Null <: UriResource](clazz: Class[U],
                                                        id: Any)
    : Future[Boolean] = {
        backend.store.exists(UriResource.getZoomClass(clazz), id)
    }

    protected def createResource[U >: Null <: UriResource](resource: U)
    : Response = {
        val message = toProto(resource)
        log.debug("CREATE: {}", makeReadable(message))
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
        log.debug("UPDATE: {}", makeReadable(message))
        tryWrite {
            backend.store.update(message)
            response
        }
    }

    protected def deleteResource(clazz: Class[_ <: UriResource], id: Any,
                                 response: Response = OkNoContentResponse)
    : Response = {
        log.debug("DELETE: {}: {}", UriResource.getZoomClass(clazz),
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
                log.debug("CREATE: {}", makeReadable(msg))
                CreateOp(msg)
            case Update(resource) =>
                val msg = toProto(resource)
                log.debug("UPDATE: {}", makeReadable(msg))
                UpdateOp(msg)
            case Delete(clazz, id) =>
                log.debug("DELETE: {}:{}", UriResource.getZoomClass(clazz),
                         id.asInstanceOf[AnyRef])
                DeleteOp(UriResource.getZoomClass(clazz), id)
            case CreateNode(path) =>
                log.debug("CREATE NODE: {}", path)
                CreateNodeOp(path, null)
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
                throw new WebApplicationException(INTERNAL_SERVER_ERROR)
        }
        resource.setBaseUri(uriInfo.getBaseUri)
        resource
    }

    protected def toProto[U >: Null <: UriResource](resource: U): Message = {
        try {
            ZoomConvert.toProto(resource, resource.getZoomClass)
        } catch {
            case e: ConvertException =>
                log.error("Failed to convert resource {} to message {}",
                          resource, resource.getZoomClass, e)
                throw new InternalServerErrorHttpException(
                    "Can't handle resource " +  resource)
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
