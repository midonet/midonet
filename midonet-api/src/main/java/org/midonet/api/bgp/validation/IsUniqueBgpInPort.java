/*
 * Copyright 2013 Midokura Pte. Ltd.
 */

package org.midonet.api.bgp.validation;

import org.midonet.api.validation.MessageProperty;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = BgpInPortUniqueValidator.class)
@Documented
public @interface IsUniqueBgpInPort {

    String message() default MessageProperty.BGP_NOT_UNIQUE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
