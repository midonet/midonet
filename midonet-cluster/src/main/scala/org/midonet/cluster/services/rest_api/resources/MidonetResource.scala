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
import java.util.concurrent.atomic.AtomicInteger
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
import org.eclipse.jetty.http.HttpStatus.METHOD_NOT_ALLOWED_405
import org.slf4j.LoggerFactory.getLogger

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.data.ZoomConvert.ConvertException
import org.midonet.cluster.data.storage._
import org.midonet.cluster.data.util.ZkOpLock
import org.midonet.cluster.rest_api.ResponseUtils.buildErrorResponse
import org.midonet.cluster.rest_api._
import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowGet, AllowList, AllowUpdate}
import org.midonet.cluster.rest_api.models.UriResource
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.util.SequenceDispenser
import org.midonet.cluster.util.logging.ProtoTextPrettifier.makeReadable
import org.midonet.cluster.{ZookeeperLockFactory, restApiLog, restApiResourceLog}
import org.midonet.midolman.state._
import org.midonet.util.reactivex._

object MidonetResource {

    private final val log = getLogger(restApiLog)
    private final val StorageAttempts = 3

    private final val lockOpNumber = new AtomicInteger(1)

    final val Timeout = 30 seconds
    final val OkResponse = Response.ok().build()
    final val OkNoContentResponse = Response.noContent().build()
    final def OkCreated(uri: URI) = Response.created(uri).build()

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
                                          lockFactory: ZookeeperLockFactory,
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
        Logger(getLogger(restApiResourceLog(getClass)))

    private val validator = resContext.validator
    protected val store = resContext.backend.store
    protected val stateStore = resContext.backend.stateStore
    protected val uriInfo = resContext.uriInfo

    /* Determines whether a zookeeper lock is needed when performing
       CRUD operations. This variable can be overridden in subclasses. */
    protected val zkLockNeeded = true

    class ResourceTransaction(val tx: Transaction) {

        def get[U >: Null <: UriResource](clazz: Class[U], id: Any): U = tryRead {
            fromProto(tx.get(UriResource.getZoomClass(clazz), id), clazz)
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
        val produces = getAnnotation(classOf[AllowGet])
        if (!produces.value().contains(accept)) {
            log.info("Media type {} not acceptable", accept)
            throw new WebApplicationException(UNSUPPORTED_MEDIA_TYPE)
        }
        getFilter(getResource(tag.runtimeClass.asInstanceOf[Class[T]], id))
    }

    @GET
    def list(@HeaderParam("Accept") accept: String): JList[T] = {
        val produces = getAnnotation(classOf[AllowList])
        if (!produces.value().contains(accept)) {
            log.info("Media type {} not acceptable", accept)
            throw new WebApplicationException(UNSUPPORTED_MEDIA_TYPE)
        }
        val ids = listIds
        val list = if (ids eq null) {
            listFilter(listResources(tag.runtimeClass.asInstanceOf[Class[T]]))
        } else {
            listFilter(listResources(tag.runtimeClass.asInstanceOf[Class[T]], ids))
        }
        list.asJava
    }

    /**
      * This method acquires a ZooKeeper lock to perform updates to storage.
      * This is to prevent races with other components modifying the topology
      * concurrently that may result in a [[ConcurrentModificationException]].
      */
    private def zkLock[R](f: => Response): Response = {
        val lock = new ZkOpLock(resContext.lockFactory, lockOpNumber.getAndIncrement,
                                ZookeeperLockFactory.ZOOM_TOPOLOGY)

        if (!zkLockNeeded) return f

        try lock.acquire() catch {
            case NonFatal(t) =>
                log.info("Could not acquire storage lock.", t)
                throw new ServiceUnavailableHttpException(
                    "Could not acquire lock for storage operation.")
        }
        try {
            f
        } catch {
            case e: NotFoundException =>
                log.info(e.getMessage)
                buildErrorResponse(NOT_FOUND, e.getMessage)
            case e: ObjectReferencedException =>
                log.info(e.getMessage)
                buildErrorResponse(CONFLICT, e.getMessage)
            case e: ReferenceConflictException =>
                log.info(e.getMessage)
                buildErrorResponse(CONFLICT, e.getMessage)
            case e: ObjectExistsException =>
                log.info(e.getMessage)
                buildErrorResponse(CONFLICT, e.getMessage)
            /* In case the object was modified elsewhere without acquiring
               a lock. */
            case e: ConcurrentModificationException =>
                log.info("Write to storage failed due to contention", e)
                Response.status(CONFLICT).build()
            case e: WebApplicationException =>
                e.getResponse
            case NonFatal(e) =>
                log.info("Unhandled exception", e)
                buildErrorResponse(INTERNAL_SERVER_ERROR, e.getMessage)
        } finally {
            lock.release()
        }
    }

    @POST
    def create(t: T, @HeaderParam("Content-Type") contentType: String)
    : Response = {
        val consumes = getAnnotation(classOf[AllowCreate])
        if (!consumes.value().contains(contentType)) {
            log.info("Media type {} not supported", contentType)
            throw new WebApplicationException(UNSUPPORTED_MEDIA_TYPE)
        }

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
               @HeaderParam("Content-Type") contentType: String): Response = {
        val consumes = getAnnotation(classOf[AllowUpdate])
        if (!consumes.value().contains(contentType)) {
            log.info("Media type {} not supported", contentType)
            throw new WebApplicationException(UNSUPPORTED_MEDIA_TYPE)
        }

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


    protected def transaction(): ResourceTransaction = {
        new ResourceTransaction(store.transaction())
    }

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
                    "Can't handle resource " +  resource)
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
        var attempt = 1
        while (attempt <= StorageAttempts) {
            try {
                return zkLock {
                    val tx = transaction()
                    val response = f(tx)
                    tx.commit()
                    response
                }
            } catch {
                case e: WebApplicationException => throw e
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
                    log.warn(s"Write $attempt of $StorageAttempts failed " +
                             "due to a concurrent modification ({}): retrying",
                             e.getMessage)
                    Thread.sleep(10)
                    attempt += 1
                case NonFatal(e) =>
                    log.error("Unhandled exception", e)
                    return buildErrorResponse(INTERNAL_SERVER_ERROR,
                                              e.getMessage)
            }
        }
        log.error(s"Failed to write to store after $StorageAttempts attempts")
        Response.status(CONFLICT).build()
    }
}
