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
package org.midonet.cluster.rest_api.validation;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.Provider;
import com.google.inject.Singleton;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorFactory;

@Singleton
public class GuiceConstraintValidatorFactory implements
        ConstraintValidatorFactory {

    private final Injector injector;

    @Inject
    public GuiceConstraintValidatorFactory(final Injector injector) {
        this.injector = injector;
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(
            final Class<T> key) {
        return injector.getInstance(key);
    }

}
