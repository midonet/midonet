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
package org.midonet.cluster.rest_api.models;

import java.net.InetAddress;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

// TODO: Has no correspondence in Zoom, can be removed?
@XmlRootElement
public class Interface {

    public UUID hostId;
    public String name;
    public String mac;
    public int mtu;
    public int status;
    public Type type;
    public String endpoint;
    public String portType;
    public InetAddress[] addresses;

    public enum Type {
        Physical, Virtual, Tunnel, Unknown
    }

}
