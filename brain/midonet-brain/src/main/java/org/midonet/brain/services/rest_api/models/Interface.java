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
package org.midonet.brain.services.rest_api.models;

import java.net.InetAddress;
import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomClass;
import org.midonet.cluster.data.ZoomEnum;
import org.midonet.cluster.data.ZoomEnumValue;
import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.models.Topology;
import org.midonet.cluster.util.IPAddressUtil;

@ZoomClass(clazz = Topology.Host.Interface.class)
@XmlRootElement
public class Interface {

    public UUID hostId;
    @ZoomField(name = "name")
    public String name;
    @ZoomField(name = "mac")
    public String mac;
    @ZoomField(name = "mtu")
    public int mtu;
    public int status;
    @ZoomField(name = "type")
    public Type type;
    public String endpoint;
    public String portType;
    @ZoomField(name = "addresses", converter = IPAddressUtil.Converter.class)
    public InetAddress[] addresses;

    @ZoomEnum(clazz = Topology.Host.Interface.Type.class)
    public enum Type {
        @ZoomEnumValue(value = "PHYSICAL")
        Physical,
        @ZoomEnumValue(value = "VIRTUAL")
        Virtual,
        @ZoomEnumValue(value = "TUNNEL")
        Tunnel,
        @ZoomEnumValue(value = "UNKNOWN")
        Unknown
    }

}
