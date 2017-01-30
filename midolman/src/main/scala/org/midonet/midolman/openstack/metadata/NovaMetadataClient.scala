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

package org.midonet.midolman.openstack.metadata

import java.io.InputStream

import com.sun.jersey.api.client.config.DefaultClientConfig
import com.sun.jersey.api.client.{Client, ClientResponse, UniformInterfaceException}
import com.sun.jersey.core.impl.provider.entity.ByteArrayProvider

import org.apache.commons.io.IOUtils

object Conv {
    implicit def toHexstring(bytes: Array[Byte]): String =
        (bytes flatMap (x => f"$x%02x")).mkString

    implicit def toBytes(string: String): Array[Byte] =
        string.getBytes
}

/*
 * Nova Metadata API Client
 *
 * Nova Metadata API recognizes some extension headers to allows proxies
 * like us to be implemented.  Another example of such a proxy is
 * neutron-metadata-agent.
 *
 * X-Tenant-ID:
 *   Keystone tenant ID.  (== Neutron port tenant_id)
 *
 * X-Instance-ID:
 *   Nova instance ID.  (== Neutron port device_id)
 *
 * X-Instance-ID-Signature:
 *   HMAC-SHA256 signature of X-Instance-ID.
 *
 * X-Forwarded-For:
 *   The real instance address.
 *   Nova metadata API uses this as local-ipv4 when fixed_ips are not
 *   available.  It's also passed to vendordata_driver.
 */

class NovaMetadataClientException(message: String)
    extends RuntimeException(message)

object NovaMetadataClient {

    import Conv._

    private def signInstanceId(sharedSecret: String,
                               instanceId: String): String =
        Hmac.hmac(sharedSecret, instanceId)

    private def readAll(stream: InputStream) = IOUtils.toByteArray(stream)

    def proxyRequest(method: String,
                     path: String,
                     content: InputStream,
                     remoteAddr: String,
                     novaMetadataUrl: String,
                     sharedSecret: String): String = {
        Log debug s"$method request from $remoteAddr for path $path"
        InstanceInfoMap getByAddr remoteAddr match {
            case Some(info) =>
                Log debug s"Request matches instance $info"
                proxyRequest(method, path, content,
                             info, novaMetadataUrl, sharedSecret)
            case None =>
                /*
                 * This shouldn't happen normally as datapath flows are
                 * installed using InstanceInfo.
                 */
                Log warn s"Received request from unknown address $remoteAddr"
                throw new NovaMetadataClientException(
                    s"Unknown remote address $remoteAddr")
        }
    }

    private def proxyRequest(method: String,
                     path: String,
                     content: InputStream,
                     info: InstanceInfo,
                     novaMetadataUrl: String,
                     sharedSecret: String): String = {
        val instanceIdSig = signInstanceId(sharedSecret, info.instanceId)
        val cc = new DefaultClientConfig(classOf[ByteArrayProvider])
        val client = Client.create(cc)
        val url = novaMetadataUrl + path
        Log debug s"$method request from instance:${info.instanceId} to $url"

        val resource = client.resource(url)
            .header("X-Tenant-ID", info.tenantId)
            .header("X-Instance-ID", info.instanceId)
            .header("X-Instance-ID-Signature", instanceIdSig)
            .header("X-Forwarded-For", info.address)
        try {
            val response = method match {
                case "GET" => resource.get(classOf[ClientResponse])
                // POST is used by nova blueprint get-password
                // https://blueprints.launchpad.net/nova/+spec/get-password
                case "POST" => resource.post(classOf[ClientResponse],
                                             readAll(content))
                case _ => throw new NovaMetadataClientException(
                    s"Unsupported method $method")
            }
            if (response.getStatus >= 300) {
                throw new UniformInterfaceException(response)
            }

            val encoding =
                response.getType.getParameters.getOrDefault("charset", "UTF-8")
            val data = IOUtils.toString(response.getEntityInputStream,
                                        encoding)
            Log debug s"Response for instance:${info.instanceId} " +
                      s"status:${response.getStatus} " +
                      s"length:${response.getLength} " +
                      s"media-type:${response.getType} " +
                      s"data:$data"
            data
        } catch {
            case e: UniformInterfaceException =>
                Log error s"Unexpected HTTP response: $e for request: " +
                          s"$url $info"
                throw e
        }
    }
}
