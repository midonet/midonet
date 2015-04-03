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

package org.midonet.api.auth;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import org.midonet.brain.services.rest_api.auth.AbstractStateFilter;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.cluster.data.SystemState;

import static org.midonet.cluster.data.SystemState.Availability.READONLY;

@Singleton
public class DataClientStateFilter extends AbstractStateFilter {

    @Inject
    protected DataClient dataClient;

    @Override
    public boolean isStateReadonly() throws StateAccessException {
        SystemState systemState = dataClient.systemStateGet();
        return READONLY.toString().equals(systemState.getAvailability());
    }

}
