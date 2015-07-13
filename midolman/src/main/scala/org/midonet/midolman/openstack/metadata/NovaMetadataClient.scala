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
import org.slf4j.{Logger, LoggerFactory}

object Conv {
    implicit def to_hexstring(bytes: Array[Byte]): String =
        (bytes flatMap (x => f"${x}%02x")).mkString

    implicit def to_bytes(string: String): Array[Byte] =
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

object NovaMetadataClient {
    private val log: Logger =
        LoggerFactory getLogger NovaMetadataClient.getClass

    import Conv._

    def sign_instance_id(shared_secret: String, instanceId: String): String =
        Hmac.hmac(shared_secret, instanceId)

    def get_metadata(path: String, remoteAddr: String,
                     nova_metadata_url: String,
                     shared_secret: String): String = {
        log debug s"Forwarding a request from ${remoteAddr}"
        val Some(info) = InstanceInfoMap getByAddr remoteAddr
        log debug s"InstanceInfo ${info}"
        get_metadata(path, info, nova_metadata_url, shared_secret)
    }

    def get_metadata(path: String, info: InstanceInfo,
                     nova_metadata_url: String,
                     shared_secret: String): String = {
        val instanceIdSig = sign_instance_id(shared_secret, info.instanceId)
        val client = new Client
        val r = client.resource(nova_metadata_url + path)
            .header("X-Tenant-ID", info.tenantId)
            .header("X-Instance-ID", info.instanceId)
            .header("X-Instance-ID-Signature", instanceIdSig)
            .header("X-Forwarded-For", info.addr)
        r get classOf[String]
    }
}
