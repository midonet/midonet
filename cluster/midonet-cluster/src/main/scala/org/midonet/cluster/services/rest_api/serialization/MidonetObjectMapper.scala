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

package org.midonet.cluster.services.rest_api.serialization

import java.io.IOException
import javax.ws.rs.WebApplicationException

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import org.apache.commons.lang.StringUtils
import org.codehaus.jackson.`type`.JavaType
import org.codehaus.jackson.map.introspect.BasicBeanDescription
import org.codehaus.jackson.map.{DeserializationConfig, JsonMappingException, ObjectMapper}
import org.codehaus.jackson.{JsonParseException, JsonParser}

import org.midonet.cluster.rest_api.{BadRequestHttpException, ResponseUtils}
import org.midonet.cluster.rest_api.annotation.JsonError
import org.midonet.cluster.rest_api.validation.MessageProperty

/**
 * An [[ObjectMapper]] for MidoNet API resources. This implementation provides
 * a custom handling of serialization exceptions, such that it returns error
 * responses and messages equivalent to legacy validators.
 */
class MidonetObjectMapper extends ObjectMapper {

    @throws(classOf[IOException])
    @throws(classOf[JsonParseException])
    @throws(classOf[JsonMappingException])
    protected override def _readValue(cfg: DeserializationConfig,
                                      jp: JsonParser,
                                      valueType: JavaType): AnyRef = {
        try {
            super._readValue(cfg, jp, valueType)
        } catch {
            case e: JsonMappingException if e.getPath.size() > 0 =>
                val ref = e.getPath.get(0)
                if ((ref eq null) || (ref.getFrom eq null)) throw e

                val error = getError(cfg, valueType, ref.getFieldName)
                if (error eq null) throw e

                val message = if (StringUtils.isNotEmpty(error.message())) {
                    MessageProperty.getMessage(error.message())
                } else error.value()
                throw new WebApplicationException(
                    ResponseUtils.buildErrorResponse(error.status(), message))
            case e: JsonMappingException =>
                throw new BadRequestHttpException(e.getMessage)
            case NonFatal(e) => throw e
        }
    }

    private def getError(cfg: DeserializationConfig, valueType: JavaType,
                         fieldName: String): JsonError = {
        try {
            val beanDesc = cfg.introspect[BasicBeanDescription](valueType)
            beanDesc.findProperties().asScala
                    .find(_.getName == fieldName) flatMap { property =>
                if ((property.getField ne null) &&
                    (property.getField.getAnnotated ne null)) {
                    val field = property.getField.getAnnotated
                    Option(field.getAnnotation(classOf[JsonError]))
                } else None
            } orNull
        } catch {
            case NonFatal(_) => null
        }
    }

}