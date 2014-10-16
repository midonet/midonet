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

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.google.inject.Inject;

import org.midonet.api.network.Route;
import org.midonet.api.validation.MessageProperty;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Port;

public class RouteNextHopPortConstraintValidator implements
        ConstraintValidator<NextHopPortValid, Route> {
    @Inject
    DataClient dataClient;

    @Override
    public void initialize(NextHopPortValid constraintAnnotation) {
    }

    @Override
    public boolean isValid(Route value, ConstraintValidatorContext context) {

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.ROUTE_NEXT_HOP_PORT_NOT_NULL)
                .addNode("nextHopPort").addConstraintViolation();

        if (!value.isNormal()) {
            // This validation only applies for 'normal' route.
            return true;
        }

        if (value.getNextHopPort() == null)
            return false;

        try {
            Port<?, ?> p = dataClient.portsGet(value.getNextHopPort());
            return p != null && p.getDeviceId().equals(value.getRouterId());
        } catch (StateAccessException e) {
            return false;
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization exception occurred in validation", e);
        }
    }
}
