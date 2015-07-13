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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Hmac {
    def hmac(key: Array[Byte], data: Array[Byte]) = {
        val algo = "HmacSHA256"
        // NOTE(yamamoto): The following ":+ 0" is a hack to avoid
        // "java.lang.IllegalArgumentException: Empty key" from
        // SecretKeySpec.  While an empty key is bad for security,
        // it's the default value of nova metadata api, which we
        // need to communicate with.
        val keyspec = new SecretKeySpec(key :+ (0: Byte), 0, key.length, algo)
        val mac = Mac getInstance algo
        mac init keyspec
        mac doFinal data
    }
}

object Conv {
    implicit def to_hexstring(bytes: Array[Byte]): String =
        (bytes flatMap (x=>f"${x}%02x")).mkString

    implicit def to_bytes(string: String): Array[Byte] =
        string.getBytes
}

object NovaMetadataClient {
    private val log: Logger =
        LoggerFactory.getLogger(NovaMetadataClient.getClass)

    val metadata_proxy_shared_secret = ""  // XXX config
    val nova_metadata_url = "http://ubu7:8775"  // XXX config

    import Conv._

    def sign_instance_id(instanceId: String): String =
        Hmac.hmac(metadata_proxy_shared_secret, instanceId)

    def get_metadata(path: String, remoteAddr: String) = {
        // vm_addr: source address of the request
        //   nova metadata api uses this as local-ipv4 when fixed_ips are
        //   not available.  also passed to vendordata_driver.
        // tenant_id: keystone tenant id  (== neutron port tenant_id)
        // instance_id: nova instance id  (== neutron port device_id)
        val Some(info) = InstanceInfoMap getByAddr remoteAddr
        log.info(s"Remote-Addr: ${remoteAddr}, VM-Addr: ${info.addr}, " +
                 s"Port-Id: ${info.portId}, Tenant-Id: ${info.tenantId}, " +
                 s"Instance-Id: ${info.instanceId}")
        val instanceIdSig = sign_instance_id(info.instanceId)
        val client = new Client
        val r = client.resource(nova_metadata_url + path)
            .header("X-Tenant-ID", info.tenantId)
            .header("X-Instance-ID", info.instanceId)
            .header("X-Instance-ID-Signature", instanceIdSig)
            .header("X-Forwarded-For", info.addr)
        r get classOf[String]
    }
}
