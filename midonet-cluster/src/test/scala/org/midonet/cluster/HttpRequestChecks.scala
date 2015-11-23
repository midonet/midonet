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

package org.midonet.cluster

import java.net.URI
import scala.collection.JavaConversions._
import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.Response.Status.{NO_CONTENT, CREATED}

import scala.reflect.ClassTag

import com.sun.jersey.api.client.ClientResponse
import com.sun.jersey.test.framework.JerseyTest
import org.scalatest.ShouldMatchers

import org.midonet.cluster.rest_api.models.{ServiceContainer, UriResource}
import org.midonet.cluster.rest_api.rest_api.FuncTest

/** Utility assertions for JerseyTests to be used in REST API tests.
  */
trait HttpRequestChecks extends JerseyTest with ShouldMatchers {

    /**
      * Assert a successful POST the given DTO to the base resource URI.  We
      * will take care to check the right status code (201) that should be
      * standardized for all creates.
      *
      * @param dto the DTO
      * @param typeUri the root URI for the resource type
      * @param mediaType the media type
      * @return the value of the LOCATION header
      */
    def postAndAssertOk(dto: UriResource,
                        typeUri: URI, mediaType: String): URI = {
        val postResp = postAndAssertStatus(dto, typeUri, mediaType, CREATED)
        postResp.getLocation shouldBe dto.getUri
        postResp.getLocation
    }

    def postAndAssertStatus(dto: UriResource, typeUri: URI, mediaType: String,
                            status: Response.Status): ClientResponse = {
        val postResp = resource().uri(typeUri)
            .`type`(mediaType)
            .post(classOf[ClientResponse], dto)
        postResp.getStatus shouldBe status.getStatusCode
        postResp
    }

    def putAndAssertStatus(dto: UriResource, mediaType: String,
                           status: Int): ClientResponse = {
        val postResp = resource().uri(dto.getUri)
            .`type`(mediaType)
            .put(classOf[ClientResponse], dto)
        postResp.getStatus shouldBe status
        postResp
    }

    def get[T](uri: URI, mediaType: String)(implicit ct: ClassTag[T]): T = {
        val r = resource().uri(uri)
            .accept(mediaType)
            .get(classOf[ClientResponse])
        r.getStatus shouldBe Status.OK.getStatusCode
        r.getEntity(ct.runtimeClass.asInstanceOf[Class[T]])
    }

    /** Assert a successful GET the resource at the given DTO, checking that
      * the relevant headers and result codes are set.
      */
    def getAndAssertOk[T <: UriResource](uri: URI, mediaType: String)
                                        (implicit ct: ClassTag[T]): T = {
        val e = get(uri, mediaType)(ct)
        e.setBaseUri(resource().getURI)
        e.getUri shouldBe uri
        e
    }

    def listAndAssertOk[T <: UriResource](uri: URI, mediaType: String)
                                         (implicit ct: ClassTag[T]): List[T] = {
        val r = resource().uri(uri)
            .accept(mediaType)
            .get(classOf[ClientResponse])
        r.getStatus shouldBe Status.OK.getStatusCode

        val raw = r.getEntity(classOf[String])
        val theType = FuncTest.objectMapper.getTypeFactory
            .constructParametrizedType(classOf[java.util.List[_]],
                                       classOf[java.util.List[_]],
                                       ct.runtimeClass)
        FuncTest.objectMapper.readValue[java.util.List[T]](raw, theType)
                             .toList
    }

    /** Assert a successful PUT of the resource, checking that the relevant
      * headers are set correctly and the status code meets the standard.
      */
    def putAndAssertOk(dto: UriResource, mediaType: String): Unit = {
        putAndAssertStatus(dto, mediaType, NO_CONTENT.getStatusCode)
    }

    /** Assert a successful DELETE of the resource, checking that the
      * relevant headers are set correctly.
      */
    def deleteAndAssertOk(uri: URI): Unit = {
        val delResp = resource().uri(uri).delete(classOf[ClientResponse])
        delResp.getStatus shouldBe NO_CONTENT.getStatusCode
    }

}
