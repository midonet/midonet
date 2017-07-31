/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.endpoint.comm

import java.io.{File, RandomAccessFile}
import java.net.URLDecoder
import java.text.SimpleDateFormat
import java.util._

import javax.activation.MimetypesFileTypeMap

import scala.util.Try

import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory

import io.netty.buffer.Unpooled
import io.netty.channel._
import io.netty.handler.codec.http.HttpMethod._
import io.netty.handler.codec.http.HttpResponseStatus._
import io.netty.handler.codec.http.HttpVersion._
import io.netty.handler.codec.http._
import io.netty.handler.stream.ChunkedFile
import io.netty.util.CharsetUtil

/**
  * A netty handler for serving static files based on HTTP GET requests.
  *
  * Implements 'If-Modified-Since' header to take advantage of browser
  * cache.
  *
  * Scala adaptation of the code from:
  * http://netty.io/4.1/xref/io/netty/example/http/file/HttpStaticFileServerHandler.html
  *
  * @param baseDir Base directory where all served files are located.
  * @param uriPrefix Some URI prefix to remove before trying to locate
  *                          the file.
  * @param dirIndexes Sequence of files to try when serving a directory.
  * @param cachePolicy Partial function that matches file paths (relative to
  *                    baseDir, with leading slash) with number of seconds for
  *                    which they should be cached.
  */
class HttpStaticFileServerHandler(baseDir: String, uriPrefix: String = "",
                                  dirIndexes: Seq[String] = Nil,
                                  cachePolicy: PartialFunction[String, Int] =
                                    PartialFunction.empty)
    extends SimpleChannelInboundHandler[FullHttpRequest] {

    import HttpStaticFileServerHandler.{DefaultHttpCacheSeconds, Log, MimeTypesMap}

    private val dateFormatter =
        HttpStaticFileServerHandler.createHttpDateFormatter
    private val normalizedBaseDir = FilenameUtils.normalize(baseDir)

    private val sanitizedUriPrefix = uriPrefix.stripSuffix("/")

    override def channelRead0(ctx: ChannelHandlerContext,
                              request: FullHttpRequest): Unit = {
        if (!request.decoderResult.isSuccess) {
            sendError(ctx, BAD_REQUEST)
            return
        }

        if (request.method() != GET) {
            sendError(ctx, METHOD_NOT_ALLOWED)
            return
        }

        val uri = request.uri.stripPrefix(sanitizedUriPrefix)

        // If the handler has a prefix, but the uri remains unchanged, that
        // means this request doesn't match the handler's prefix and should be
        // 403d
        if (sanitizedUriPrefix.nonEmpty && uri == request.uri) {
            sendError(ctx, FORBIDDEN)
            return
        }

        val uriDecoder = new QueryStringDecoder(uri)
        val decodedPath = uriDecoder.path
        val uriPath = if (decodedPath.nonEmpty) decodedPath else "/"
        val sanitizedUriOption = sanitizeUri(uriPath)

        val sanitizedUri = sanitizedUriOption.getOrElse {
            sendError(ctx, FORBIDDEN)
            return
        }

        // Join the relative sanitizedUri with the baseDir path
        val path = FilenameUtils.concat(normalizedBaseDir, sanitizedUri)

        val originalFile = new File(path)

        // If the file we were asked to serve is a directory but the request URI
        // path doesn't have a trailing slash, redirect to the canonical URL for
        // that directory, i.e., with a trailing slash. This fixes a bunch of
        // problems related to relative paths in index HTML pages.
        // See https://httpd.apache.org/docs/current/mod/mod_dir.html#directoryslash
        if (originalFile.isDirectory && !decodedPath.endsWith("/")) {
            // Don't allow redirections to paths containing multiple //
            val uriWithoutTrailingSlash = sanitizedUri.stripSuffix("/")
            val canonicalUri =
                if (uriWithoutTrailingSlash.nonEmpty)
                    sanitizedUriPrefix + "/" + uriWithoutTrailingSlash + "/"
                else
                    sanitizedUriPrefix + "/"
            sendPermanentRedirect(ctx, canonicalUri)
            return
        }

        // Attempt to find correct file to serve
        val fileOption: Option[File] = getValidFile(originalFile)
        val file = fileOption.getOrElse {
            sendError(ctx, NOT_FOUND)
            return
        }

        val ifModifiedSince = Option(request.headers
            .get(HttpHeaderNames.IF_MODIFIED_SINCE))

        ifModifiedSince.filter(!_.isEmpty).foreach { iMS =>
            val ifModifiedSinceDate = dateFormatter.parse(iMS)

            // Work with seconds as that's the granularity of HTTP dates
            val ifModifiedSinceSeconds = ifModifiedSinceDate.getTime / 1000
            val fileLastModifiedSeconds = file.lastModified() / 1000

            if (ifModifiedSinceSeconds == fileLastModifiedSeconds) {
                sendNotModified(ctx)
                return
            }
        }

        val rafTry = Try(new RandomAccessFile(file, "r"))

        val raf = rafTry.getOrElse {
            sendError(ctx, NOT_FOUND)
            return
        }

        // If raf is valid, we found the file so return it
        try {
            val response = new DefaultHttpResponse(HTTP_1_1, OK)

            HttpUtil.setContentLength(response, raf.length)
            setContentTypeHeader(response, file)
            setDateAndCacheHeaders(response, file)

            ctx.write(response)

            sendFileContents(ctx, raf)
        } catch {
            // raf will be automatically closed by sendFileContents once
            // everything is sent. Only time we need to close it manually
            // is if there's an exception
            case e: Throwable =>
                Log.debug(e.getMessage, e)
                raf.close()
                throw e
        }
    }

    override def exceptionCaught(ctx: ChannelHandlerContext,
                                 cause: Throwable): Unit = {
        Log.warn("Error serving static file", cause)

        if (ctx.channel.isActive) {
            sendError(ctx, INTERNAL_SERVER_ERROR)
        }
    }

    private def getValidFile(file: File): Option[File] =
        if (file.isHidden || !file.exists) {
            None
        }
        else if (file.isDirectory) {
            dirIndexes.iterator.map(new File(file, _)).collectFirst {
                // Only collect if "<dir>/<index_file>" is valid
                case f: File if getValidFile(f).orNull == f => f
            }
        }
        else if (file.isFile) {
            Some(file)
        }
        else {
            None
        }


    private def sanitizeUri(uri: String): Option[String] = {
        val decodedUri = URLDecoder.decode(uri, "UTF-8")

        if (decodedUri.isEmpty || !decodedUri.startsWith("/")) {
            None
        } else {
            // Normalize things like /./ and /../ in relation to the virtual
            // root of decodedUri (i.e., as if there´s nothing above that uri)
            // Also remove duplicate /
            val normalizedUri = FilenameUtils.normalize(
                // Drop repeated '/' at the beginning to handle paths like '//a'
                // without FilenameUtils thinking they are a prefix
                decodedUri.dropWhile(_ == '/'))

            if (normalizedUri == null) {
                None
            } else {
                // Remove system-defined prefixes that could allow us to treat
                // normalizedUri as an absolute path
                Some(normalizedUri.substring(
                    FilenameUtils.getPrefixLength(normalizedUri)
                ))
            }
        }
    }

    private def sendError(ctx: ChannelHandlerContext,
                          status: HttpResponseStatus) = {
        val response = new DefaultFullHttpResponse(
            HTTP_1_1,
            status,
            Unpooled.copiedBuffer("Failure: " + status + "\r\n",
                                  CharsetUtil.UTF_8))

        response.headers.set(HttpHeaderNames.CONTENT_TYPE,
                             "text/plain; charset=UTF-8")

        ctx.writeAndFlush(response)
    }

    private def sendNotModified(ctx: ChannelHandlerContext) = {
        val response = new DefaultFullHttpResponse(HTTP_1_1, NOT_MODIFIED)

        setDateHeader(response)

        ctx.writeAndFlush(response)
    }

    private def sendPermanentRedirect(ctx: ChannelHandlerContext,
                                      targetUrl: String) = {
        val response = new DefaultFullHttpResponse(HTTP_1_1, MOVED_PERMANENTLY)

        response.headers.set(HttpHeaderNames.LOCATION,
                             targetUrl)

        ctx.writeAndFlush(response)
    }

    private def setDateHeader(response: HttpResponse) = {
        val now = Calendar.getInstance

        response.headers.set(HttpHeaderNames.DATE,
                             dateFormatter.format(now.getTime))
        now
    }

    private def setDateAndCacheHeaders(response: HttpResponse, file: File) = {
        setDateHeader(response)

        val path = file.getPath.stripPrefix(normalizedBaseDir)
        val cacheTime =
            cachePolicy.applyOrElse(path,
                                    (_: String) => DefaultHttpCacheSeconds)

        response.headers.set(HttpHeaderNames.CACHE_CONTROL,
                             "private, max-age=" + cacheTime)
        response.headers.set(HttpHeaderNames.LAST_MODIFIED,
                             dateFormatter.format(new Date(file.lastModified)))
    }

    private def setContentTypeHeader(response: HttpResponse, file: File) = {
        response.headers.set(HttpHeaderNames.CONTENT_TYPE,
                             MimeTypesMap.getContentType(file.getPath))
    }

    private def sendFileContents(ctx: ChannelHandlerContext,
                                 raf: RandomAccessFile) = {
        val future = ctx.writeAndFlush(
            new HttpChunkedInput(new ChunkedFile(raf)),
            ctx.newProgressivePromise())

        if (Log.isDebugEnabled) {
            future.addListener(new ChannelProgressiveFutureListener {
                override def operationProgressed
                    (future: ChannelProgressiveFuture,
                     progress: Long,
                     total: Long): Unit = {
                    if (total < 0) {
                        // Total unknown
                        Log.debug(future.channel + " Transfer progress: " +
                                  progress)
                    } else {
                        Log.debug(future.channel + " Transfer progress: " +
                                  progress + " / " + total)
                    }
                }

                override def operationComplete
                    (future: ChannelProgressiveFuture): Unit = {
                    Log.debug(future.channel + " Transfer complete.")
                }
            })
        }

        future
    }
}

object HttpStaticFileServerHandler {
    private final val HttpDateFormat = "EEE, dd MMM yyyy HH:mm:ss zzz"
    private final val HttpDateGmtTimezone = TimeZone.getTimeZone("GMT")

    // Default is 1 day cache
    final val DefaultHttpCacheSeconds = 86400

    private final val MimeTypesMap = new MimetypesFileTypeMap

    private final val Log = LoggerFactory.getLogger(
        classOf[HttpStaticFileServerHandler])

    def createHttpDateFormatter = {
        val dateFormatter = new SimpleDateFormat(HttpDateFormat, Locale.US)
        dateFormatter.setTimeZone(HttpDateGmtTimezone)

        dateFormatter
    }
}
