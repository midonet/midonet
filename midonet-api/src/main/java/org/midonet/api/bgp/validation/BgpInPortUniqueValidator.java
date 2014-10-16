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

package org.midonet.api.bgp.validation;

import com.google.inject.Inject;
import org.midonet.api.bgp.Bgp;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class BgpInPortUniqueValidator implements
        ConstraintValidator<IsUniqueBgpInPort, Bgp>
{
    private final DataClient dataClient;

    @Inject
    public BgpInPortUniqueValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniqueBgpInPort constraintAnnotation) {
    }

    @Override
    public boolean isValid(Bgp bgp, ConstraintValidatorContext context) {
        if (bgp == null) {
            return false;
        }

        if (bgp.getPortId() == null) {
            return false;
        }

        try {
            if (!dataClient.bgpFindByPort(bgp.getPortId()).isEmpty()) {
                return false;
            }
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validating bgp", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization Error while validating bgp", e);
        }

        return true;
    }

}
