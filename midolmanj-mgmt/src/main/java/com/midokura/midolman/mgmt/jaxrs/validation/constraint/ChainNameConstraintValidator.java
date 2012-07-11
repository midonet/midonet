/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs.validation.constraint;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.mgmt.jaxrs.validation.MessageProperty;
import com.midokura.midolman.mgmt.jaxrs.validation.annotation.IsUniqueChainName;
import com.midokura.midolman.state.StateAccessException;

public class ChainNameConstraintValidator implements
        ConstraintValidator<IsUniqueChainName, Chain> {

    private final ChainDao dao;

    public ChainNameConstraintValidator(ChainDao dao) {
        this.dao = dao;
    }

    @Override
    public void initialize(IsUniqueChainName constraintAnnotation) {
    }

    @Override
    public boolean isValid(Chain value, ConstraintValidatorContext context) {

        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(
                MessageProperty.IS_UNIQUE_CHAIN_NAME).addNode("name")
                .addConstraintViolation();

        Chain chain = null;
        try {
            chain = dao.get(value.getTenantId(), value.getName());
        } catch (StateAccessException e) {
            throw new RuntimeException(
                    "State access exception occurred in validation");
        }

        // It's valid if the duplicate named chain does not exist, or
        // exists but it's the same chain.
        return (chain == null || chain.getId().equals(value.getId()));
    }
}
