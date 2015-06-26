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

package org.midonet.cluster.services.rest_api.static

import java.io.File
import java.net.URLDecoder
import java.nio.file.Files
import javax.servlet.http.{HttpServlet, HttpServletRequest, HttpServletResponse}

import org.slf4j.LoggerFactory

class StaticServlet(fromPath: String) extends HttpServlet {
    val log = LoggerFactory.getLogger("org.midonet.api.static")
    override def doGet(req: HttpServletRequest, resp: HttpServletResponse)
    : Unit = {
        val filename = URLDecoder.decode(req.getPathInfo.substring(1), "UTF-8")
        val file = new File(fromPath, filename)
        resp.setDateHeader("Expires", 0)
        resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate")
        resp.setHeader("Pragma", "no-cache")
        resp.setHeader("Content-Type", getServletContext.getMimeType(filename))
        resp.setHeader("Content-Length", String.valueOf(file.length()))
        resp.setHeader("Content-Disposition",
                       "inline; filename=\"" + file.getName + "\"")
        Files.copy(file.toPath, resp.getOutputStream)
    }
}
