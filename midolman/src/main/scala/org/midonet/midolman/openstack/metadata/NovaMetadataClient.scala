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

import com.sun.jersey.api.client.Client
import com.sun.jersey.api.client.UniformInterfaceException
import org.slf4j.Logger

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

class UnknownRemoteAddressException(remoteAddr: String)
    extends RuntimeException(s"Unknown remote address $remoteAddr")

object NovaMetadataClient {
    private val log: Logger = MetadataService.getLogger

    import Conv._

    private def signInstanceId(sharedSecret: String,
                               instanceId: String): String =
        Hmac.hmac(sharedSecret, instanceId)

    def getMetadata(path: String, remoteAddr: String,
                    novaMetadataUrl: String,
                    sharedSecret: String): String = {
        log debug s"Forwarding a request from $remoteAddr"
        InstanceInfoMap getByAddr remoteAddr match {
            case Some(info) =>
                log debug s"InstanceInfo $info"
                getMetadata(path, info, novaMetadataUrl, sharedSecret)
            case None =>
                /*
                 * This shouldn't happen normally as datapath flows are
                 * installed using InstanceInfo.
                 */
                log warn s"Got a request from unknown address $remoteAddr"
                throw new UnknownRemoteAddressException(remoteAddr)
        }
    }

    def getMetadata(path: String, info: InstanceInfo,
                    novaMetadataUrl: String,
                    sharedSecret: String): String = {
        val instanceIdSig = signInstanceId(sharedSecret, info.instanceId)
        val client = new Client
        val url = novaMetadataUrl + path
        val resource = client.resource(url)
            .header("X-Tenant-ID", info.tenantId)
            .header("X-Instance-ID", info.instanceId)
            .header("X-Instance-ID-Signature", instanceIdSig)
            .header("X-Forwarded-For", info.addr)
        try {
            resource get classOf[String]
        } catch {
            case e: UniformInterfaceException =>
                log error s"Unexpected HTTP response: $e for request: " +
                          s"$url $info"
                throw e
        }
    }
}
