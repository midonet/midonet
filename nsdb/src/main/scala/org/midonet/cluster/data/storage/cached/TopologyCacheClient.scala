/*
 * Copyright 2017 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.data.storage.cached

import java.net.URI

import javax.net.ssl.SSLContext

import scala.async.Async.async
import scala.concurrent.{ExecutionContext, Future}

import com.google.common.net.HostAndPort

import org.apache.commons.io.IOUtils
import org.apache.http.HttpException
import org.apache.http.client.methods.{CloseableHttpResponse, HttpGet}
import org.apache.http.impl.client.HttpClients

import org.midonet.cluster.services.discovery.{MidonetDiscoveryClient, MidonetDiscoverySelector, MidonetServiceURI}

import io.netty.handler.codec.http.HttpResponseStatus

trait TopologyCacheClient {
    def fetch(implicit ec: ExecutionContext): Future[Array[Byte]]
}

abstract class TopologyCacheClientBase extends TopologyCacheClient {

    private lazy val client = HttpClients.custom()
        .setSslcontext(ssl.orNull)
        .build()

    protected def ssl: Option[SSLContext]
    protected def url: URI

    override def fetch(implicit ec: ExecutionContext): Future[Array[Byte]] = {
        async {
            val srvUrl = url
            if (srvUrl == null) {
                throw new HttpException("Topology cache service unavailable")
            } else {
                client.execute(new HttpGet(srvUrl))
            }
        } map checkResponse
    }

    private def checkResponse(resp: CloseableHttpResponse): Array[Byte] = {
        lazy val code = resp.getStatusLine.getStatusCode
        lazy val ctype =
            if (resp.getEntity.getContentType == null) null
            else resp.getEntity.getContentType.getValue

        if (code != HttpResponseStatus.OK.code()) {
            throw new HttpException(
                "Topology cache client got non-OK code: " +
                HttpResponseStatus.valueOf(code))
        }
        if (ctype != "application/octet-stream") {
            throw new HttpException(
                "Topology cache client got unexpected content type: " + ctype)
        }
        IOUtils.toByteArray(resp.getEntity.getContent)
    }
}

class TopologyCacheClientImpl(srv: HostAndPort,
                              override protected val ssl: Option[SSLContext])
    extends TopologyCacheClientBase {
    import TopologyCacheClientImpl._

    private lazy val scheme = if (ssl.isDefined) "https" else "http"

    override protected val url: URI = new URI(
        scheme, null, srv.getHostText, srv.getPort,
        TopologyCacheServicePath, null, null)
}

object TopologyCacheClientImpl {
    private val TopologyCacheServicePath = "/topology-cache"
}


class TopologyCacheClientDiscovery(discovery: MidonetDiscoverySelector[MidonetServiceURI],
                                   override protected val ssl: Option[SSLContext])
    extends TopologyCacheClientBase {
    override protected def url: URI = discovery.getInstance.map(_.uri).orNull
}
