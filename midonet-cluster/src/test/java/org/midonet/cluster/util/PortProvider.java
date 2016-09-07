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
package org.midonet.cluster.util;

import java.io.IOException;
import java.net.ServerSocket;

public class PortProvider {

    /**
     * Binding a socket to port 0 makes the OS returns us a free ephemeral
     * port. Return this port so a server can binds to it.
     *
     * @return port A free port to bind a server to.
     */
    public static int getPort() {
        while (true) {
            ServerSocket ss = null;
            try {
                ss = new ServerSocket(0);
                ss.setReuseAddress(true);
                return ss.getLocalPort();
            } catch (IOException e) {
            } finally {
                if (ss != null) {
                    try {
                        ss.close();
                    } catch (IOException ioe) {}
                }
            }
        }
    }

}
