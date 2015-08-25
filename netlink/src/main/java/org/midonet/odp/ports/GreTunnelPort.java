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
package org.midonet.odp.ports;

import org.midonet.odp.DpPort;

/**
 * Description of a GRE tunnel datapath port.
 */
public class GreTunnelPort extends DpPort {

    /*
     * from OVS datapath/vport-gre.c:
     *    14B ethernet header length
     *  + 20B IPv4 header length
     *  +  4B gre csum
     *  +  4B gre key
     *  +  4B gre seq
     *  = 46B
     */
    public static final int TunnelOverhead = 46;

    public GreTunnelPort(String name) {
        super(name);
    }

    public Type getType() {
        return Type.Gre;
    }

    /** returns a new GreTunnelPort instance with empty options */
    public static GreTunnelPort make(String name) {
        return new GreTunnelPort(name);
    }

}
