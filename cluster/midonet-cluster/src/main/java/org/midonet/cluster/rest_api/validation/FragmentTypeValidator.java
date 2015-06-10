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

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import org.midonet.cluster.rest_api.models.Condition;
import org.midonet.cluster.rest_api.models.ForwardNatRule;
import org.midonet.midolman.rules.FragmentPolicy;

import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_INVALID_FOR_L4_RULE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.FRAG_POLICY_INVALID_FOR_NAT_RULE;
import static org.midonet.cluster.rest_api.validation.MessageProperty.getMessage;
import static org.midonet.midolman.rules.FragmentPolicy.UNFRAGMENTED;
import static org.midonet.packets.IPFragmentType.Later;

public class FragmentTypeValidator
    implements ConstraintValidator<IsValidFragmentType, Condition> {

    @Override
    public void initialize(IsValidFragmentType constraintAnnotation) {

    }

    @Override
    public boolean isValid(Condition cond,
                           ConstraintValidatorContext context) {

        if (cond == null || cond.fragmentPolicy == null) {
            return true;
        }

        if (cond.fragmentPolicy.equalsIgnoreCase("UNDEFINED")) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                getMessage(
                    MessageProperty.FRAG_POLICY_UNDEFINED,
                    (Object) cond)
            ).addConstraintViolation();
            return false;
        }

        if (cond instanceof ForwardNatRule) {
            return isValid((ForwardNatRule) cond, context);
        } else {
            FragmentPolicy fp = FragmentPolicy.valueOf(
                cond.fragmentPolicy.toUpperCase());
            if (cond.hasL4Fields() && fp.accepts(Later)) {
                context.disableDefaultConstraintViolation();
                context.buildConstraintViolationWithTemplate(
                    getMessage(FRAG_POLICY_INVALID_FOR_L4_RULE, (Object) cond)
                ).addConstraintViolation();
                return false;
            }
        }

        return true;

    }

    private boolean isValid(ForwardNatRule fnr,
                            ConstraintValidatorContext context) {
        boolean unfragmentedOnly = !fnr.isFloatingIp() || fnr.hasL4Fields();

        FragmentPolicy fp = FragmentPolicy.valueOf(
            fnr.fragmentPolicy.toUpperCase());
        if (unfragmentedOnly && fp != UNFRAGMENTED) {
            context.disableDefaultConstraintViolation();
            context.buildConstraintViolationWithTemplate(
                getMessage(FRAG_POLICY_INVALID_FOR_NAT_RULE, (Object) fnr)
            ).addConstraintViolation();
            return false;
        }

        return true;
    }

}
