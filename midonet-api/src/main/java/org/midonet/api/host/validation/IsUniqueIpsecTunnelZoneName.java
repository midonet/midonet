package org.midonet.api.host.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import javax.validation.Constraint;
import javax.validation.Payload;
import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import org.midonet.api.validation.MessageProperty;

@Target({ TYPE, ANNOTATION_TYPE })
@Retention(RUNTIME)
@Constraint(validatedBy = IpsecTunnelZoneNameValidator.class)
@Documented
public @interface IsUniqueIpsecTunnelZoneName {

    String message() default MessageProperty.IS_UNIQUE_TUNNEL_ZONE_NAME_TYPE;

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

