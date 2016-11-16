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

package org.midonet.cluster.services.rest_api.resources

import java.lang.annotation.Annotation
import java.net.URI
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
import com.typesafe.scalalogging.Logger

import org.eclipse.jetty.http.HttpStatus.METHOD_NOT_ALLOWED_405
import org.slf4j.LoggerFactory.getLogger

import org.midonet.cluster._
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
import org.midonet.midolman.state._
import org.midonet.util.reactivex._

object MidonetResource {

    final def OkResponse = Response.ok().build()
    final def OkNoContentResponse = Response.noContent().build()
    final def OkCreated(uri: URI) = Response.created(uri).build()
    final def OkCreated(uri: URI, entity: UriResource) = {
        Response.created(uri).entity(entity).build()
    }

    final val DefaultHandler: PartialFunction[Response, Response] = {
        case r => r
    }
    final val DefaultCatcher: PartialFunction[Throwable, Response] = {
        case e: WebApplicationException => e.getResponse
    }

    final class FutureOps[T](val future: Future[T]) extends AnyVal {
        def getOrThrow(implicit timeout: FiniteDuration, log: Logger): T = {
            tryRead { Await.result(future, timeout) }
        }
    }

    protected[resources] def tryRead[T](f: => T)(implicit log: Logger): T = {
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
                val message = "Resource read timeout"
                log.warn(message, e)
                throw new ServiceUnavailableHttpException(message)
        }
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

    case class ResourceContext @Inject() (config: RestApiConfig,
                                          backend: MidonetBackend,
                                          executionContext: ExecutionContext,
                                          uriInfo: UriInfo,
                                          validator: Validator,
                                          seqDispenser: SequenceDispenser)

}

/**
  * CRUD operations need to be performed atomically and are done by
  * acquiring a ZooKeeper lock. This is to prevent races with
  * the agent (Midolman), more specifically the health monitor.
  *
  * TODO: The agent should never write to topology elements.  All code
  * violating this principle will be refactored out of here shortly (MNA-1068).
  */
abstract class MidonetResource[T >: Null <: UriResource]
                              (resContext: ResourceContext)
                              (implicit tag: ClassTag[T]) {

    protected implicit val executionContext = resContext.executionContext
    protected implicit val requestTimeout =
        resContext.config.requestTimeoutMs millis
    protected final implicit val log =
        Logger(getLogger(restApiResourceLog(getClass)))

    private def validator = resContext.validator
    private def store = resContext.backend.store
    protected def stateStore = resContext.backend.stateStore
    protected def stateTableStore = resContext.backend.stateTableStore
    protected def uriInfo = resContext.uriInfo

    class ResourceTransaction(val tx: Transaction) {

        def get[U >: Null <: UriResource](clazz: Class[U], id: Any): U = tryRead {
            fromProto(tx.get(UriResource.getZoomClass(clazz), id), clazz)
        }

        def getAll[U >: Null <: UriResource](clazz: Class[U], ids: Seq[Any])
        : Seq[U] = tryRead {
            tx.getAll(UriResource.getZoomClass(clazz), ids)
                .map(fromProto(_, clazz))
        }

        def list[U >: Null <: UriResource](clazz: Class[U]): Seq[U] = tryRead {
            tx.getAll(UriResource.getZoomClass(clazz))
                .map(fromProto(_, clazz))
        }

        def list[U >: Null <: UriResource](clazz: Class[U], ids: Seq[Any])
        : Seq[U] = tryRead {
            tx.getAll(UriResource.getZoomClass(clazz), ids)
                .map(fromProto(_, clazz))
        }

        def create[U >: Null <: UriResource](resource: U): Unit = {
            val message = toProto(resource)
            log.debug("TX CREATE: {}", makeReadable(message))
            tx.create(message)
        }

        def update[U >: Null <: UriResource](resource: U): Unit = {
            val message = toProto(resource)
            log.debug("TX UPDATE: {}", makeReadable(message))
            tx.update(message, null)
        }

        def delete(clazz: Class[_ <: UriResource], id: Any): Unit = {
            log.debug("TX DELETE: {}: {}", UriResource.getZoomClass(clazz),
                      id.asInstanceOf[AnyRef])
            tx.delete(UriResource.getZoomClass(clazz), id, ignoresNeo = true)
        }

        def commit(): Unit = {
            tx.commit()
        }

    }

    @GET
    @Path("{id}")
    def get(@PathParam("id") id: String,
            @HeaderParam("Accept") accept: String): T = {
        validateMediaType(accept, getAnnotation(classOf[AllowGet]).value())
        getFilter(getResource(tag.runtimeClass.asInstanceOf[Class[T]], id))
    }

    @GET
    def list(@HeaderParam("Accept") accept: String): JList[T] = {
        validateMediaType(accept, getAnnotation(classOf[AllowList]).value())
        val ids = listIds
        val list = if (ids eq null) {
            listFilter(listResources(tag.runtimeClass.asInstanceOf[Class[T]]))
        } else {
            listFilter(listResources(tag.runtimeClass.asInstanceOf[Class[T]], ids))
        }
        list.asJava
    }

    @POST
    def create(t: T, @HeaderParam("Content-Type") contentType: String)
    : Response = {
        validateMediaType(contentType, getAnnotation(classOf[AllowCreate]).value())

        t.setBaseUri(uriInfo.getBaseUri)

        tryResponse(handleCreate, catchCreate) {
            tryTx { tx =>
                t.create()
                throwIfViolationsOn(t)
                createFilter(t, tx)
                OkCreated(t.getUri)
            }
        }
    }

    @PUT
    @Path("{id}")
    def update(@PathParam("id") id: String, t: T,
               @HeaderParam("Content-Type") contentType: String)
    : Response = {
        validateMediaType(contentType, getAnnotation(classOf[AllowUpdate]).value())

        val clazz = tag.runtimeClass.asInstanceOf[Class[T]]
        tryResponse(handleUpdate, catchUpdate) {
            tryTx { tx =>
                val current = tx.get(clazz, id)
                throwIfViolationsOn(t)
                updateFilter(t, current, tx)
                OkNoContentResponse
            }
        }
    }

    @DELETE
    @Path("{id}")
    def delete(@PathParam("id") id: String): Response = {
        tryResponse(handleDelete, catchDelete) {
            tryTx { tx =>
                deleteFilter(id, tx)
                OkNoContentResponse
            }
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

    protected def getFilter(t: T): T = t

    protected def listIds: Seq[Any] = null

    protected def listFilter(list: Seq[T]): Seq[T] = list

    protected def createFilter(t: T, tx: ResourceTransaction): Unit = {
        tx.create(t)
    }

    protected def updateFilter(to: T, from: T, tx: ResourceTransaction): Unit = {
        tx.update(to)
    }

    protected def deleteFilter(id: String, tx: ResourceTransaction): Unit = {
        tx.delete(tag.runtimeClass.asInstanceOf[Class[T]], id)
    }

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
    : Seq[U] = {
        store.getAll(UriResource.getZoomClass(clazz))
             .map(_.map(fromProto(_, clazz)))
             .getOrThrow
    }

    protected def listResources[U >: Null <: UriResource](clazz: Class[U],
                                                          ids: Seq[Any])
    : Seq[U] = {
        store.getAll(UriResource.getZoomClass(clazz), ids)
             .map(_.map(fromProto(_, clazz)))
             .getOrThrow
    }

    protected def getResource[U >: Null <: UriResource](clazz: Class[U], id: Any)
    : U = {
        store.get(UriResource.getZoomClass(clazz), id)
             .map(fromProto(_, clazz))
             .getOrThrow
    }

    protected def getResources[U >: Null <: UriResource](clazz: Class[U], ids: Seq[Any])
    : Seq[U] = {
        store.getAll(UriResource.getZoomClass(clazz), ids)
             .map { r => r.map(fromProto(_, clazz)) }
             .getOrThrow
    }

    protected def getResourceState[U >: Null <: UriResource](host: String,
                                                             clazz: Class[U],
                                                             id: Any, key: String)
    : StateKey = {
        stateStore.getKey(host, UriResource.getZoomClass(clazz), id, key)
                  .asFuture
                  .getOrThrow
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
                    "Cannot handle resource " +  resource)
        }
    }

    /** Guaranteed to return a non-null value, or throw 405
      */
    private def getAnnotation[U >: Null <: Annotation](clazz: Class[U]): U = {
        var c: Class[_] = getClass
        while (classOf[MidonetResource[_]].isAssignableFrom(c)) {
            val annotation = c.getAnnotation(clazz)
            if (annotation ne null) {
                return annotation
            }
            c = c.getSuperclass
        }
        throw new WebApplicationException(METHOD_NOT_ALLOWED_405)
    }

    protected def tryTx(f: (ResourceTransaction) => Response): Response = {
        try {
            store.tryTransaction { tx =>
                f(new ResourceTransaction(tx))
            }
        } catch {
            case e: WebApplicationException =>
                e.getResponse
            case e: NotFoundException =>
                log.warn(e.getMessage)
                buildErrorResponse(NOT_FOUND, e.getMessage)
            case e: ObjectReferencedException =>
                log.warn(e.getMessage)
                buildErrorResponse(CONFLICT, e.getMessage)
            case e: ReferenceConflictException =>
                log.warn(e.getMessage)
                buildErrorResponse(CONFLICT, e.getMessage)
            case e: ObjectExistsException =>
                log.warn(e.getMessage)
                buildErrorResponse(CONFLICT, e.getMessage)
            case e: ConcurrentModificationException =>
                log.warn(e.getMessage)
                buildErrorResponse(CONFLICT, e.getMessage)
            case NonFatal(e) =>
                log.error("Unhandled exception", e)
                buildErrorResponse(INTERNAL_SERVER_ERROR, e.getMessage)
        }
    }

    @throws[WebApplicationException]
    private def validateMediaType(value: String, allowed: Array[String])
    : Unit = {
        try {
            val mediaType = MediaType.valueOf(value)
            for (in <- allowed if MediaType.valueOf(in).isCompatible(mediaType)) {
                return
            }
            log.info("Media type {} not acceptable", value)
            throw new WebApplicationException(NOT_ACCEPTABLE)

        } catch {
            case e: IllegalArgumentException =>
                throw new WebApplicationException(NOT_ACCEPTABLE)
        }
    }

}
