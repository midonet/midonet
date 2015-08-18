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

package org.midonet.migrator.converters;

import org.midonet.cluster.rest_api.models.Condition;
import org.midonet.cluster.rest_api.models.TraceRequest;

import static org.midonet.migrator.converters.ConditionDataConverter.fillFromSimulationData;

public class TraceRequestDataConverter {

    public static TraceRequest fromData(
        org.midonet.cluster.data.TraceRequest traceRequest) {

        TraceRequest tr = new TraceRequest();
        tr.id = traceRequest.getId();
        tr.name = traceRequest.getName();
        tr.deviceType = traceRequest.getDeviceType();
        tr.deviceId = traceRequest.getDeviceId();
        tr.creationTimestampMs = traceRequest.getCreationTimestampMs();
        tr.limit = traceRequest.getLimit();
        tr.enabled = (traceRequest.getEnabledRule() != null);
        tr.condition = new Condition();
        fillFromSimulationData(tr.condition, traceRequest.getCondition());
        return tr;
    }

}
