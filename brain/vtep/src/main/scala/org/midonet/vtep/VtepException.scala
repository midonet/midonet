/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.vtep

/**
 * A checked exception for VTEPs
 */
class VtepException private(val vtep: VtepEndPoint, exc: Exception)
    extends Exception(exc) {
    def this(vtep: VtepEndPoint) = this(vtep, VtepException.create())
    def this(vtep: VtepEndPoint, msg: String) =
        this(vtep, VtepException.create(s"VTEP $vtep : " + msg))
    def this(vtep: VtepEndPoint, exc: Throwable) =
        this(vtep, VtepException.create(exc))
    def this(vtep: VtepEndPoint, msg: String, cause: Throwable) =
        this(vtep, VtepException.create(s"VTEP $vtep : " + msg, cause))
    protected def this(ve: VtepException) = this(ve.vtep, VtepException.create())
}

object VtepException {
    private def create(): Exception = new Exception()
    private def create(msg: String): Exception = new Exception(msg)
    private def create(cause: Throwable): Exception = new Exception(cause)
    private def create(msg: String, cause: Throwable): Exception =
        new Exception(msg, cause)
    private def create(ve: VtepException): Exception = ve
}

/**
 * A checked exception for VTEP states
 */
class VtepStateException protected(ve: VtepException) extends VtepException(ve) {
    def this(vtep: VtepEndPoint) = this(new VtepException(vtep))
    def this(vtep: VtepEndPoint, msg: String) =
        this(new VtepException(vtep, msg))
    def this(vtep: VtepEndPoint, cause: Throwable) =
        this(new VtepException(vtep, cause))
    def this(vtep: VtepEndPoint, msg: String, cause: Throwable) =
        this(new VtepException(vtep, msg, cause))
}

