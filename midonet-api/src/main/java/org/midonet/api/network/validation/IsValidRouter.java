/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.network.validation;

import org.midonet.api.validation.MessageProperty;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ METHOD, FIELD, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = RouterIdValidator.class)
@Documented
public @interface IsValidRouter {

    String message() default MessageProperty.ROUTER_ID_IS_INVALID;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
