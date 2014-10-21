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
package org.midonet.api.network.validation;

import java.util.Objects;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.api.network.Bridge;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

public class VxlanPortIdIntact implements
        ConstraintValidator<IsVxlanPortIdIntact, Bridge> {

    private static final Logger
        log = LoggerFactory.getLogger(VxlanPortIdIntact.class);

    private final DataClient dataClient;

    @Inject
    public VxlanPortIdIntact(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsVxlanPortIdIntact constraintAnnotation) {
    }

    @Override
    public boolean isValid(Bridge b, ConstraintValidatorContext context) {
        if (b == null) {
            return false;
        }
        if (b.getId() == null) {
            if (b.getVxLanPortId() != null) {
                log.debug("New bridge cannot bring a vxlan port id");
                return false;
            }
            return true;
        }
        try {
            org.midonet.cluster.data.Bridge oldB =
                dataClient.bridgesGet(b.getId());
            if (oldB == null) { // the bridge doesn't seem to be there..
                return b.getVxLanPortId() == null; // always true at this point
            }

            if (!Objects.equals(b.getVxLanPortId(), oldB.getVxLanPortId())) {
                log.debug("Trying to change bridge's vxlan port id from API");
                return false;
            }
            return true;
        } catch (StateAccessException | SerializationException e) {
            throw new RuntimeException("Error while validation port", e);
        }

    }
}
