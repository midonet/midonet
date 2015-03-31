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

// TODO: zoom class
@XmlRootElement
public class HealthMonitor extends ZoomObject {

    @ZoomField(name = "id", converter = UUIDUtil.Converter.class)
    private UUID id;
    @NotNull

    // TODO: @VerifyEnumValue(HealthMonitorType.class)
    @ZoomField(name = "type")
    private String type;

    @ZoomField(name = "delay")
    private int delay;

    @ZoomField(name = "timeout")
    private int timeout;

    @ZoomField(name = "maxRetries")
    private int maxRetries;

    @ZoomField(name = "adminStateUp")
    private boolean adminStateUp = true;

    // TODO: @VerifyEnumValue(LBStatus.class)
    @ZoomField(name = "status")
    public String status = LBStatus.ACTIVE.toString();

}
