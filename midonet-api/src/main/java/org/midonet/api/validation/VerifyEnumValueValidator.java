/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.api.validation;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

/**
 * Implementation for the user-defined constraint annotation @VerifyValue
 * This is a general purpose validator which verifies the value for any enum
 * If an Enum object has a getValue() method it will validate based on the
 * value of the Enum else will use the EnumConstant
 *
 * Borrowed from Bhakti Mehta's code on the following GitHub page:
 *
 *   http://git.io/1czaQQ
 *
 * @author Bhakti Mehta
 */
public class VerifyEnumValueValidator
        implements ConstraintValidator<VerifyEnumValue, Object> {

    Class<? extends Enum<?>> enumClass;

    public void initialize(final VerifyEnumValue enumObject) {
        enumClass = enumObject.value();

    }

    /**
     * Checks if the value specified is null or valid.
     *
     * @param myval  The value for the object
     * @param constraintValidatorContext
     * @return true if the input is valid, otherwise false
     */
    public boolean isValid(
            final Object myval,
            final ConstraintValidatorContext constraintValidatorContext) {
        if (myval == null) {
            return true;
        } else if (enumClass != null) {
            Enum<?>[] enumValues = enumClass.getEnumConstants();
            Object enumValue = null;

            for (Enum<?> enumerable : enumValues)   {
                if (myval.equals(enumerable.toString())) {
                    return true;
                }
                enumValue = getEnumValue(enumerable);
                if ((enumValue != null)
                        && (myval.toString().equals(enumValue.toString())))  {
                    return true;
                }
            }
            constraintValidatorContext.disableDefaultConstraintViolation();
            constraintValidatorContext.buildConstraintViolationWithTemplate(
                    MessageProperty.getMessage(
                            MessageProperty.VALUE_IS_NOT_IN_ENUMS,
                            (Object)enumValues)
            ).addConstraintViolation();
        }
        return false;
    }


    /**
     * Invokes the getValue() method for enum if present
     *
     * @param enumerable The Enum object
     * @return  returns the value of enum from getValue() or
     *          enum constant
     */
    private Object getEnumValue(Enum<?> enumerable) {
        try {
            for (Method method: enumerable.getClass().getDeclaredMethods()) {
                if (method.getName().equals("getValue")) {
                    return method.invoke(enumerable);
                }
            }
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }




}