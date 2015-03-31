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
package org.midonet.brain.services.vladimir.models;

import java.util.UUID;

import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;

import org.midonet.cluster.data.ZoomField;
import org.midonet.cluster.data.ZoomObject;
import org.midonet.cluster.util.UUIDUtil;
import org.midonet.midolman.state.l4lb.LBStatus;
import org.midonet.midolman.state.l4lb.PoolProtocol;

// TODO: Make ZOOM CLASS
@XmlRootElement
public class Pool extends ZoomObject {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    private UUID id;

    @ZoomField(name = "healthMonitorId", converter = UUIDUtil.Converter.class)
    private UUID healthMonitorId;

    @NotNull
    @ZoomField(name = "loadBalancerId", converter = UUIDUtil.Converter.class)
    private UUID loadBalancerId;

    @ZoomField(name = "protocol", converter = UUIDUtil.Converter.class)
    private String protocol = PoolProtocol.TCP.toString();

    @NotNull
    private String lbMethod;

    @ZoomField(name = "adminStateUp", converter = UUIDUtil.Converter.class)
    private boolean adminStateUp = true;

    @ZoomField(name = "status", converter = UUIDUtil.Converter.class)
    private String status = LBStatus.ACTIVE.toString();
}
