/**
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.host.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.validation.Constraint;
import javax.validation.Payload;
import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import org.midonet.api.validation.MessageProperty;

@Target({ FIELD, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = TunnelZoneNameValidator.class)
@Documented
public @interface UniqueTunnelZoneName {

    String message() default MessageProperty.UNIQUE_TUNNEL_ZONE_NAME_TYPE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

