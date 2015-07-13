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

package org.midonet.midolman.openstack.metadata.proxy

import com.sun.jersey.api.client.Client
import com.sun.jersey.api.client.UniformInterfaceException
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

object MetadataClient {
    val metadata_proxy_shared_secret = ""  // XXX config
    val nova_metadata_url = "http://ubu7:8775"  // XXX config

    import Conv._

    def sign_instance_id(instance_id: String): String =
        Hmac.hmac(metadata_proxy_shared_secret, instance_id)

    def get_metadata(path: String, remote_addr: String) = {
        // vm_addr: source address of the request
        //   nova metadata api uses this as local-ipv4 when fixed_ips are
        //   not available.  also passed to vendordata_driver.
        // tenant_id: keystone tenant id  (== neutron port tenant_id)
        // instance_id: nova instance id  (== neutron port device_id)
        val vm_addr = "1.0.0.3"  // XXX consult VM IP mappings
        val tenant_id = "1e1713b3b92f434d912d07fda4d0892f"  // XXX consult ZK
        val instance_id = "e8b5285e-2c02-4399-94a2-811ad2340cbc"  // XXX consult ZK
        val instance_id_sig = sign_instance_id(instance_id)
        val client = new Client
        val r = client.resource(nova_metadata_url + path)
            .header("X-Tenant-ID", tenant_id)
            .header("X-Instance-ID", instance_id)
            .header("X-Instance-ID-Signature", instance_id_sig)
            .header("X-Forwarded-For", vm_addr)
        r get classOf[String]
    }
}

import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import org.eclipse.jetty.server.{Request, Server}
import org.eclipse.jetty.server.handler.AbstractHandler;

class MetadataProxy extends AbstractHandler {
    def handle(target: String, baseReq: Request, req: HttpServletRequest,
               res: HttpServletResponse) = {
        baseReq setHandled true
        try {
            val result = MetadataClient.get_metadata(req getPathInfo,
                                                     req getRemoteAddr)
            res.getWriter println result
        } catch {
            case e: UniformInterfaceException =>
                res.sendError(e.getResponse.getStatus, e.getMessage)
        }
    }
}

// object MyMain extends App {
//     val server = new Server(80)
//     server.setHandler(new MetadataProxy)
//     server.start
//     server.join
// }
