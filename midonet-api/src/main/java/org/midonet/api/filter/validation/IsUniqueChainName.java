/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.filter.validation;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import javax.validation.Constraint;
import javax.validation.Payload;

import org.midonet.api.validation.MessageProperty;

@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = ChainNameConstraintValidator.class)
@Documented
public @interface IsUniqueChainName {

    String message() default MessageProperty.IS_UNIQUE_CHAIN_NAME;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
