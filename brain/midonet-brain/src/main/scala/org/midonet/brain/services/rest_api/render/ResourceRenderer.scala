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

package org.midonet.brain.services.rest_api.render

import java.net.URI

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

import com.google.protobuf.{Message, MessageOrBuilder}
import org.slf4j.LoggerFactory

import org.midonet.brain.services.rest_api.models.{Host, UriResource}
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Topology
import org.midonet.cluster.services.MidonetBackend

/**
  * This class captures the transformation of DTOs <-> PROTOs allowing to
  * inject custom transformations for more complex types where the translation
  * is not direct (and thus provided for automatically by ZoomConvert).
  */
class ResourceRenderer(backend: MidonetBackend) {

    implicit private val _bknd = backend

    /** Render a new UriResource from the corresponding Protobuf, using
      * the specialized transformation, if it exists, or the default 1-1
      * transformation via ZoomConvert otherwise.
      */
    def from[T >: Null <: UriResource, U <: MessageOrBuilder]
        (proto: U, pojoClass: Class[T], baseUri: URI): T = {
        if (proto == null) {
            throw new NullPointerException
        }
        val d: UriResource = render(proto).getOrElse {
            ZoomConvert.fromProto(proto, pojoClass)
        }
        d.setBaseUri(baseUri)
        d.asInstanceOf[T]
    }

    /** Deconstruct the Protobuf(s) from the corresponding DTO, using
      * the specialized transformation, if it exists, or the default 1-1
      * transformation via ZoomConvert otherwise.
      */
    def to[T <: UriResource, U <: MessageOrBuilder]
        (dto: T, protoClass: Class[U]): U = {
        if (dto == null) {
            throw new NullPointerException
        }
        unrender(dto).getOrElse {
            ZoomConvert.toProto(dto, protoClass)
        }.asInstanceOf[U]
    }

    /** Perform the specialized de-construction of the UriResource into
      * its corresponding Protobuf, if there is such specialization.
      */
    def unrender[T <: UriResource](dto: T): Option[MessageOrBuilder] = dto match {
        case _ => None
    }

    /** Perform the specialized build of the UriResource from its Protobuf, if
      * there is such specialization.
      */
    def render[T <: MessageOrBuilder](p: T): Option[UriResource] = p match {
        case h: Topology.Host => Some(HostBuilder.apply(h))
        case _ => None
    }
}

trait DtoBuilder[T <: UriResource] {
    import com.google.common.util.concurrent.MoreExecutors._
    protected implicit val ec = ExecutionContext.fromExecutor(sameThreadExecutor())
    def apply(proto: Message)(implicit backend: MidonetBackend): T
}

/** Build the Host DTO from its Proto. It is required to incorporate the
  * alive property that resides in one of the State nodes, separate from
  * topology.
  */
object HostBuilder extends DtoBuilder[Host] {

    val log = LoggerFactory.getLogger("org.midonet.rest_api.host")
    override def apply(proto: Message)
                      (implicit backend: MidonetBackend): Host = {
        assert(proto.isInstanceOf[Topology.Host])
        log.debug("Custom handler")
        val h = ZoomConvert.fromProto(proto, classOf[Host])
        h.alive = Await.result (
            backend.ownershipStore.getOwners(classOf[Topology.Host], h.id).map { os =>
                log.debug("HOST: " + os)
                os.nonEmpty
            }, 3.seconds
        )
        h
    }
}

