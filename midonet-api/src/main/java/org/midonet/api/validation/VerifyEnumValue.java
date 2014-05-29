/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.validation.Constraint;
import javax.validation.Payload;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * A general purpose annotation used to verify
 * any Enum objects
 *
 * Borrowed from Bhakti Mehta's code on the following GitHub page:
 *
 *   http://git.io/pqWJ7Q
 *
 * @author Bhakti Mehta
 */
@Retention(RUNTIME)
@Target({FIELD, METHOD})
@Documented
@Constraint(validatedBy = VerifyEnumValueValidator.class)
public @interface VerifyEnumValue {

    String message() default MessageProperty.VALUE_IS_INVALID;
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    Class<? extends Enum<?>> value();

}