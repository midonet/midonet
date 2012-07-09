/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.jaxrs.validation;

import java.lang.reflect.Constructor;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorFactory;

import com.midokura.midolman.mgmt.data.DaoFactory;
import com.midokura.midolman.mgmt.data.dao.BridgeDao;
import com.midokura.midolman.mgmt.data.dao.ChainDao;
import com.midokura.midolman.mgmt.data.dao.RouterDao;
import com.midokura.midolman.mgmt.jaxrs.validation.constraint.BridgeNameConstraintValidator;
import com.midokura.midolman.mgmt.jaxrs.validation.constraint.ChainNameConstraintValidator;
import com.midokura.midolman.mgmt.jaxrs.validation.constraint.RouterNameConstraintValidator;

@SuppressWarnings({ "rawtypes" })
public class CustomConstraintValidatorFactory implements
        ConstraintValidatorFactory {

    private final DaoFactory daoFactory;

    public CustomConstraintValidatorFactory() {
        this.daoFactory = null;
    }

    public CustomConstraintValidatorFactory(DaoFactory daoFactory) {
        this.daoFactory = daoFactory;
    }

    @Override
    public <T extends ConstraintValidator<?, ?>> T getInstance(Class<T> key) {
        T instance = null;
        try {

            if (key == RouterNameConstraintValidator.class) {
                Class[] argsClass = new Class[] { RouterDao.class };
                Constructor<T> constructor = key.getConstructor(argsClass);
                instance = constructor.newInstance(daoFactory.getRouterDao());
            } else if (key == BridgeNameConstraintValidator.class) {
                Class[] argsClass = new Class[] { BridgeDao.class };
                Constructor<T> constructor = key.getConstructor(argsClass);
                instance = constructor.newInstance(daoFactory.getBridgeDao());
            } else if (key == ChainNameConstraintValidator.class) {
                Class[] argsClass = new Class[] { ChainDao.class };
                Constructor<T> constructor = key.getConstructor(argsClass);
                instance = constructor.newInstance(daoFactory.getChainDao());
            } else {
                instance = key.newInstance();
            }
        } catch (Exception e) {
            throw new RuntimeException("Class could not be constructed.", e);
        }

        return instance;
    }

}
