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
package org.midonet.api.host.validation;

import java.util.List;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;
import org.midonet.api.host.HostInterfacePort;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

public class HostInterfacePortValidator implements
        ConstraintValidator<IsHostInterfaceUnused, HostInterfacePort> {

    private final DataClient dataClient;

    @Inject
    public HostInterfacePortValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsHostInterfaceUnused isHostInterfaceUnused) {
    }

    @Override
    public boolean isValid(HostInterfacePort value, ConstraintValidatorContext context) {

        if (value == null) {
            return false;
        }

        try {
            List<VirtualPortMapping> mappings =
                dataClient.hostsGetVirtualPortMappingsByHost(value.getHostId());
            VirtualPortMapping mapping = null;
            for (VirtualPortMapping hip : mappings) {
                if (hip.getLocalDeviceName().equals(value.getInterfaceName())) {
                    mapping = hip;
                    break;
                }
            }
            return (mapping == null) ||
                    mapping.getVirtualPortId().equals(value.getPortId());
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validation host", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization error while validating host", e);
        }

    }
}
