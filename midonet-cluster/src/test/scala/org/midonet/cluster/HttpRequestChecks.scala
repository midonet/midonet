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

import javax.ws.rs.core.Response
import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.Response.Status.{CREATED, NO_CONTENT}

import scala.collection.JavaConversions._
import scala.reflect.ClassTag

import com.sun.jersey.api.client.{ClientResponse, WebResource}

import org.scalatest.Matchers

import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.rest_api.FuncTest
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

/** Utility assertions for JerseyTests to be used in REST API tests.
  */
trait HttpRequestChecks extends Matchers {

    def resource: WebResource

    private def mediaTypes = Map[Class[_], (String, String)](
        classOf[Chain] -> (
            APPLICATION_CHAIN_JSON, APPLICATION_CHAIN_COLLECTION_JSON),
        classOf[Host] -> (
            APPLICATION_HOST_JSON_V3, APPLICATION_HOST_COLLECTION_JSON_V3),
        classOf[Port] -> (
            APPLICATION_PORT_V3_JSON, APPLICATION_PORT_V3_COLLECTION_JSON),
        classOf[QosPolicy] -> (
            APPLICATION_QOS_POLICY_JSON,
            APPLICATION_QOS_POLICY_COLLECTION_JSON),
        classOf[QosRuleBandwidthLimit] -> (
            APPLICATION_QOS_RULE_BW_LIMIT_JSON,
            APPLICATION_QOS_RULE_BW_LIMIT_COLLECTION_JSON),
        classOf[QosRuleDscp] -> (
            APPLICATION_QOS_RULE_DSCP_JSON,
            APPLICATION_QOS_RULE_DSCP_COLLECTION_JSON),
        classOf[Router] -> (
            APPLICATION_ROUTER_JSON_V3, APPLICATION_ROUTER_COLLECTION_JSON_V3),
        classOf[RouterPort] -> (
            APPLICATION_PORT_V3_JSON, APPLICATION_PORT_V3_COLLECTION_JSON),
        classOf[ServiceContainer] ->(
            APPLICATION_SERVICE_CONTAINER_JSON,
            APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON),
        classOf[ServiceContainerGroup] -> (
            APPLICATION_SERVICE_CONTAINER_GROUP_JSON,
            APPLICATION_SERVICE_CONTAINER_GROUP_COLLECTION_JSON)
    )

    def postAndAssertStatus(dto: UriResource, typeUri: URI,
                            status: Response.Status): ClientResponse = {
        postAndAssertStatus(dto, typeUri, status.getStatusCode)
    }

    def postAndAssertStatus(dto: UriResource, typeUri: URI,
                            status: Int): ClientResponse = {
        val postResp = resource.uri(typeUri)
            .`type`(mediaTypes(dto.getClass)._1)
            .post(classOf[ClientResponse], dto)
        postResp.getStatus shouldBe status
        postResp
    }


    def putAndAssertStatus(dto: UriResource, status: Status): ClientResponse = {
        val postResp = resource.uri(dto.getUri)
            .`type`(mediaTypes(dto.getClass)._1)
            .put(classOf[ClientResponse], dto)
        postResp.getStatus shouldBe status.getStatusCode
        postResp
    }

    def getAndAssertStatus[T <: UriResource](uri: URI, status: Status)
                                            (implicit ct: ClassTag[T])
    : ClientResponse = {
        val r = resource.uri(uri)
            .accept(mediaTypes(ct.runtimeClass)._1)
            .get(classOf[ClientResponse])
        r.getStatus shouldBe status.getStatusCode
        r
    }

    def get[T <: UriResource](uri: URI)(implicit ct: ClassTag[T]): T = {
        val r = getAndAssertStatus(uri, Status.OK)
        r.getEntity(ct.runtimeClass.asInstanceOf[Class[T]])
    }

    /**
      * Assert a successful POST the given DTO to the base resource URI.  We
      * will take care to check the right status code (201) that should be
      * standardized for all creates.
      *
      * @param dto the DTO
      * @param typeUri the root URI for the resource type
      * @return the value of the LOCATION header
      */
    def postAndAssertOk(dto: UriResource, typeUri: URI): URI = {
        val postResp = postAndAssertStatus(dto, typeUri, CREATED)
        postResp.getLocation shouldBe dto.getUri
        postResp.getLocation
    }

    def postAndAssertConflict(dto: UriResource, typeUri: URI): Unit = {
        postAndAssertStatus(dto, typeUri, Status.CONFLICT)
    }

    /** Assert a successful GET the resource at the given DTO, checking that
      * the relevant headers and result codes are set.
      */
    def getAndAssertOk[T <: UriResource](uri: URI)
                                        (implicit ct: ClassTag[T]): T = {
        val e = get[T](uri)
        e.setBaseUri(resource.getURI)
        e.getUri shouldBe uri
        e
    }

    def listAndAssertOk[T <: UriResource](uri: URI)
                                         (implicit ct: ClassTag[T]): List[T] = {
        val r = resource.uri(uri)
            .accept(mediaTypes(ct.runtimeClass)._2)
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
    def putAndAssertOk(dto: UriResource): Unit = {
        putAndAssertStatus(dto, NO_CONTENT)
    }

    /** Assert a successful DELETE of the resource, checking that the
      * relevant headers are set correctly.
      */
    def deleteAndAssertOk(uri: URI): Unit = {
        deleteAndAssertStatus(uri, NO_CONTENT)
    }

    def deleteAndAssertGone[T <: UriResource](uri: URI)
                                             (implicit ct: ClassTag[T])
    : Unit = {
        deleteAndAssertOk(uri)
        getAndAssertStatus[T](uri, Status.NOT_FOUND)
    }

    /** Assert a failed DELETE of the resource, checking that the
      * relevant headers are set correctly.
      */
    def deleteAndAssertStatus(uri: URI, status: Status): Unit = {
        val delResp = resource.uri(uri).delete(classOf[ClientResponse])
        delResp.getStatus shouldBe status.getStatusCode
    }

}
