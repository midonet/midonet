/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dto.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class DtoError {

    private String message;
    private int code;
    private List<Map<String, String>> violations = new ArrayList<Map<String, String>>();

    /**
     * @return the message
     */
    public String getMessage() {
        return message;
    }

    /**
     * @param message
     *            the message to set
     */
    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * @return the code
     */
    public int getCode() {
        return code;
    }

    /**
     * @param code
     *            the code to set
     */
    public void setCode(int code) {
        this.code = code;
    }

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
