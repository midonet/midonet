/*
 * Copyright 2016 Midokura SARL
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
package org.midonet

import org.junit.runner.JUnitCore

object IntegrationTests {
    val defaultTests =
        List(classOf[org.midonet.RecircTest],
             classOf[org.midonet.midolman.host.scanner.InterfaceScannerTest],
             classOf[org.midonet.quagga.BgpdTest],
             classOf[org.midonet.midolman.vpp.VppIntegrationTest],
             classOf[org.midonet.midolman.vpp.VppControllerIntegrationTest],
             classOf[org.midonet.midolman.tc.TcIntegrationTest],
             classOf[org.midonet.midolman.vpp.
                VppControllerIntegrationTest]) map { _.getCanonicalName }

    def main(args: Array[String]): Unit = {
        if (args.length == 0) {
            JUnitCore.main(defaultTests:_*)
        } else {
            JUnitCore.main(args:_*)
        }
    }
}
