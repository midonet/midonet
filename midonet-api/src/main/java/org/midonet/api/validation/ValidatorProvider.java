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
package org.midonet.api.validation;

import com.google.inject.Inject;
import com.google.inject.Provider;

import javax.validation.Configuration;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

/**
 * Validator provider
 */
public class ValidatorProvider implements Provider<Validator> {

    private final GuiceConstraintValidatorFactory guiceValidatorFactory;

    @Inject
    public ValidatorProvider(
            GuiceConstraintValidatorFactory guiceValidatorFactory) {
        this.guiceValidatorFactory = guiceValidatorFactory;
    }

    @Override
    public Validator get() {
        // Create a custom validator and make it available for the
        // resources.
        Configuration<?> configuration = Validation.byDefaultProvider()
                .configure();
        ValidatorFactory factory = configuration
                .constraintValidatorFactory(guiceValidatorFactory)
                .buildValidatorFactory();
        return factory.getValidator();
    }

}
