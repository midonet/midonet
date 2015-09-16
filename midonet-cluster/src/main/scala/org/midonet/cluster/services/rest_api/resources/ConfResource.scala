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

import java.util.UUID

import javax.ws.rs.core.MediaType.TEXT_PLAIN
import javax.ws.rs._
import javax.ws.rs.core.Response

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import com.sun.jersey.api.core.ResourceContext
import com.typesafe.config.{ConfigValueFactory, ConfigFactory, ConfigRenderOptions, Config}

import org.midonet.cluster.rest_api.annotation.ApiResource
import org.midonet.conf.MidoNodeConfigurator

@ApiResource(version = 1)
@Path("conf")
@RequestScoped
class ConfResource @Inject()(resContext: ResourceContext,
                             configurator: MidoNodeConfigurator) {

    private val renderOptions = ConfigRenderOptions.defaults()
                                                   .setComments(false)
                                                   .setJson(true)
                                                   .setOriginComments(false)

    @GET
    @Path("schemas")
    @Produces(Array(TEXT_PLAIN))
    def listSchemas(): String = {
        render(configurator.mergedSchemas())
    }

    @GET
    @Path("nodes/{id}")
    @Produces(Array(TEXT_PLAIN))
    def getNode(@PathParam("id") nodeId: UUID): String = {
        render(configurator.centralPerNodeConfig(nodeId).closeAfter(_.get))
    }

    @POST
    @Path("nodes/{id}")
    @Consumes(Array(TEXT_PLAIN))
    def createNode(content: String, @PathParam("id") nodeId: UUID): Response = {
        val config = ConfigFactory.parseString(content)
        configurator.validate(config)
        configurator.centralPerNodeConfig(nodeId)
                    .closeAfter(_.mergeAndSet(config))
        MidonetResource.OkResponse
    }

    @DELETE
    @Path("nodes/{id}")
    def deleteNode(@PathParam("id") nodeId: UUID): Response = {
        configurator.centralPerNodeConfig(nodeId)
                    .closeAfter(_.clearAndSet(ConfigFactory.empty()))
        MidonetResource.OkResponse
    }

    @GET
    @Path("runtime/{id}")
    @Produces(Array(TEXT_PLAIN))
    def getRuntime(@PathParam("id") nodeId: UUID): String = {
        render(configurator.centralConfig(nodeId))
    }

    @GET
    @Path("templates")
    @Produces(Array(TEXT_PLAIN))
    def listTemplate(): String = {
        val templates = configurator.listTemplates
            .reduceOption((a, b) => s"$a, $b")
            .getOrElse("")
        render(ConfigFactory.parseString(s"{ templates : [ $templates ] }"))
    }

    @GET
    @Path("templates/{name}")
    @Produces(Array(TEXT_PLAIN))
    def getTemplate(@PathParam("name") name: String): String = {
        render(configurator.templateByName(name).closeAfter(_.get))
    }

    @POST
    @Path("templates/{name}")
    @Consumes(Array(TEXT_PLAIN))
    def createTemplate(content: String, @PathParam("name") name: String)
    : Response = {
        val config = ConfigFactory.parseString(content)
        configurator.validate(config)
        configurator.templateByName(name).closeAfter(_.mergeAndSet(config))
        MidonetResource.OkResponse
    }

    @DELETE
    @Path("templates/{name}")
    def deleteTemplate(@PathParam("name") name: String): Response = {
        configurator.templateByName(name)
                    .closeAfter(_.clearAndSet(ConfigFactory.empty()))
        MidonetResource.OkResponse
    }

    @GET
    @Path("template-mappings")
    @Produces(Array(TEXT_PLAIN))
    def listTemplateMappings(): String = {
        render(configurator.templateMappings)
    }

    @POST
    @Path("template-mappings/{id}")
    @Consumes(Array(TEXT_PLAIN))
    def createTemplateMappings(content: String, @PathParam("id") nodeId: UUID)
    : Response = {
        val config = ConfigFactory.parseString(content)
        configurator.assignTemplate(nodeId, config.getString(nodeId.toString))
        val templateName = configurator.templateNameForNode(nodeId)
        val resource =
            ConfigFactory.empty()
                         .withValue(nodeId.toString,
                                    ConfigValueFactory.fromAnyRef(templateName))
        Response.ok(render(resource), TEXT_PLAIN).build()
    }

    @DELETE
    @Path("template-mappings/{id}")
    def deleteTemplateMappings(@PathParam("id") nodeId: UUID): Response = {
        val mappings = configurator.templateMappings.withoutPath(nodeId.toString)
        configurator.updateTemplateAssignments(mappings)
        MidonetResource.OkResponse
    }

    private def render(config: Config): String = {
        config.root().render(renderOptions)
    }

}
