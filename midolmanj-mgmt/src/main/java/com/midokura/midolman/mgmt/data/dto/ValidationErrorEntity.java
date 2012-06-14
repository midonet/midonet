/*
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Class representing validation error.
 */
public class ValidationErrorEntity extends ErrorEntity {

    // List of Map containing the violated constraint property and its error
    // message.
    private List<Map<String, String>> violations =
            new ArrayList<Map<String, String>>();

    /**
     * @return the errors
     */
    public List<Map<String, String>> getViolations() {
        return violations;
    }

    /**
     * @param violations
     *            the violations to set
     */
    public void setViolations(List<Map<String, String>> violations) {
        this.violations = violations;
    }
}
