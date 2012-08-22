/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs.validation.constraint;

import com.google.inject.Inject;
import com.midokura.midolman.mgmt.data.dao.PortDao;
import com.midokura.midolman.mgmt.jaxrs.validation.annotation.IsValidPortId;
import com.midokura.midolman.state.StateAccessException;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;
import java.util.UUID;

public class PortIdValidator implements
        ConstraintValidator<IsValidPortId, UUID> {

    private final PortDao dao;

    @Inject
    public PortIdValidator(PortDao dao) {
        this.dao = dao;
    }

    @Override
    public void initialize(IsValidPortId constraintAnnotation) {
    }

    @Override
    public boolean isValid(UUID value, ConstraintValidatorContext context) {

        try {
            return dao.exists(value);
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validation port");
        }

    }
}
