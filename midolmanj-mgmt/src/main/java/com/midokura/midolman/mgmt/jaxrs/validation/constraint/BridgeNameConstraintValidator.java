/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs.validation.constraint;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.mgmt.jaxrs.validation.MessageProperty;
import com.midokura.midolman.mgmt.jaxrs.validation.annotation.IsUniqueBridgeName;
import com.midokura.midolman.state.StateAccessException;

public class BridgeNameConstraintValidator implements
        ConstraintValidator<IsUniqueBridgeName, Bridge> {

    private final BridgeDao dao;

    public BridgeNameConstraintValidator(BridgeDao dao) {
        this.dao = dao;
    }

    @Override
    public void initialize(IsUniqueBridgeName constraintAnnotation) {
    }

    @Override
    public boolean isValid(Bridge value, ConstraintValidatorContext context) {

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_BRIDGE_NAME).addNode("name")
                .addConstraintViolation();

        Bridge bridge = null;
        String tenantId = value.getTenantId();
        if (tenantId == null && value.getId() != null) {
            // Need to get the tenant ID
            try {
                bridge = dao.get(value.getId());
            } catch (StateAccessException e) {
                throw new RuntimeException(
                        "State access exception occurred in validation");
            }
            tenantId = bridge.getTenantId();
        }

        try {
            bridge = dao.getByName(tenantId, value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException(
                    "State access exception occurred in validation");
        }

        // It's valid if the duplicate named bridge does not exist, or
        // exists but it's the same bridge.
        return (bridge == null || bridge.getId().equals(value.getId()));
    }
}
