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

package org.midonet.cluster.data.vtep.model

import java.util.concurrent.TimeoutException

import scala.util.{Failure, Success, Try}

import org.midonet.cluster.data.vtep.{VtepConfigException, VtepStateException, VtepNotConnectedException}
import org.midonet.cluster.data.vtep.model.VtepStatus.StatusCode
import org.midonet.cluster.data.vtep.model.VtepStatus.StatusCode.StatusCode

/**
 * A container for VTEP related error information.
 * This class exists for backward compatibility with midonet-api code
 * based on old legacy vtep code.
 * TODO: To be deleted when Midonet-Api does not depend on it anymore
 */
@Deprecated
case class VtepStatus(code: StatusCode, description: String) {
    // compatibility stuff
    @Deprecated
    def getCode: StatusCode = code
    @Deprecated
    def getDescription: String = description

    def isSuccess = code == StatusCode.SUCCESS || code == StatusCode.CREATED
    override def toString: String = s"$code: $description"
    override def equals(o: Any): Boolean = o match {
        case that: VtepStatus => code == that.code
        case _ => false
    }
    override def hashCode: Int =
        if (code == null) 0 else StatusCode.calculateConsistentHashCode(code)
}

@Deprecated
object VtepStatus {
    def fromTry(t: Try[_]): VtepStatus = t match {
        case Success(_) => VtepStatus(StatusCode.SUCCESS, "")
        case Failure(exc) => exc match {
            case x: VtepNotConnectedException =>
                VtepStatus(StatusCode.NOSERVICE, x.getMessage)
            case x: VtepStateException =>
                VtepStatus(StatusCode.NOTACCEPTABLE, x.getMessage)
            case x: VtepConfigException =>
                VtepStatus(StatusCode.NOTFOUND, x.getMessage)
            case x: TimeoutException =>
                VtepStatus(StatusCode.TIMEOUT, x.getMessage)
            case x: Throwable =>
                VtepStatus(StatusCode.UNDEFINED, x.getMessage)
        }
    }
    object StatusCode extends Enumeration {
        type StatusCode = Value
        val SUCCESS = Value("Success")
        val CREATED = Value("Created")
        val BADREQUEST = Value("Bad Request")
        val UNAUTHORIZED = Value("Unauthorized")
        val FORBIDDEN = Value("Forbidden")
        val NOTFOUND = Value("Not Found")
        val NOTALLOWED = Value("Method Not Allowed")
        val NOTACCEPTABLE = Value("Request Not Acceptable")
        val TIMEOUT = Value("Request Timeout")
        val CONFLICT = Value("Resource Conflict")
        val GONE = Value("Resource Gone")
        val UNSUPPORTED = Value("Unsupported")
        val INTERNALERROR = Value("Internal Error")
        val NOTIMPLEMENTED = Value("Not Implemented")
        val NOSERVICE = Value("Service Not Available")
        val UNDEFINED = Value("Undefined Error")

        // compatibility stuff
        def calculateConsistentHashCode(c: StatusCode): Int = {
            if (c == null) 0 else c.toString.hashCode
        }
    }
}
