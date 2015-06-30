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

package org.midonet.cluster.rest_api.validation;

import java.util.UUID;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import com.google.inject.Inject;

import org.midonet.cluster.models.Topology;
import org.midonet.cluster.rest_api.models.UriResource;
import org.midonet.cluster.services.MidonetBackend;

public class PortIdValidator
    implements ConstraintValidator<ValidPortId, UUID> {

    private final MidonetBackend backend;

    @Inject
    public PortIdValidator(MidonetBackend backend) {
        this.backend = backend;
    }

    @Override
    public void initialize(ValidPortId constraintAnnotation) {}

    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {
        if (value == null) {
            return false;
        }

        try {
            Future<? extends Object> doesExist = backend.store().exists(
                    UriResource.getZoomClass(Topology.Port.class), value);
            return (Boolean) Await.result(doesExist, Duration.Inf());
        } catch (Exception ex) {
            return false;
        }
    }
}
