/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network.validation;


import com.midokura.midolman.mgmt.validation.MessageProperty;

import javax.validation.Constraint;
import javax.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

@Target({ METHOD, FIELD, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = PortIdValidator.class)
@Documented
public @interface  IsValidPortId {

    String message() default MessageProperty.PORT_ID_IS_INVALID;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};


}
