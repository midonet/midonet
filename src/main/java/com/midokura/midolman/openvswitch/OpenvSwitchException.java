/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.openvswitch;

import org.omg.CosNaming.NamingContextPackage.NotFound;

import java.util.Map;
import java.util.EnumSet;
import java.util.HashMap;

public abstract class OpenvSwitchException extends Exception {
    /**
     * Create a general exception for Open vSwitch errors with given code, the
     * error and the detail.
     *
     * @param code
     * @param error
     * @param details
     * @return An instance of OpenvSwitchException with the code and the detail
     */
    public static OpenvSwitchException create(Code code, String error, String details) {
        OpenvSwitchException r = create(code);
        r.error = error;
        r.details = details;
        return r;
    }
    /**
     * Create a general exception for Open vSwitch errors with given code and
     * the error.
     *
     * @param code
     * @param error
     * @return An instance of OpenvSwitchException with the code and the detail
     */
    public static OpenvSwitchException create(Code code, String error) {
        OpenvSwitchException r = create(code);
        r.error = error;
        return r;
    }

    /**
     * Create a general exception for Open vSwitch errors with given code.
     * @param code
     * @return An instance of OpenvSwtichException with the code.
     */
    public static OpenvSwitchException create(Code code) {
        switch (code) {
            case OVSERROR:
                return new OVSDBException();
            case OK:
            default:
                throw new IllegalArgumentException("Invalid exception code");
        }
    }

    /**
     * The enumeration of codes for Open vSwitch errors.
     */
    public static enum Code {
        OK  (0),  // OK, no problem.
        OVSERROR  (-1),  // General error of Open vSwitch
        NOTFOUND  (-2);

        private static final Map<Integer, Code> lookup
                = new HashMap<Integer, Code>();
        static {
            for (Code c: EnumSet.allOf(Code.class))
                lookup.put(c.code, c);
        }

        private final int code;
        Code(int code) {
            this.code = code;
        }

        public int intValue() { return code; }

        public static Code get(int code) {
            return lookup.get(code);
        }
    }

    /**
     * Get the message associated with the given code.
     * @param code
     * @return The message associated with the given code.
     */
    static String getCodeMessage(Code code) {
        switch (code) {
            case OVSERROR:
                return "OVSDB Error:";
            case NOTFOUND:
                return "Not Found in OVSDB:";
            case OK:
                return "ok";
            default:
                return "Unknown error " + code;
        }
    }

    // Error code
    private Code code;
    // Error
    private String error;
    // Error details
    private String details;

    public OpenvSwitchException(Code code) {
        this.code = code;
    }

    OpenvSwitchException(Code code, String error, String details) {
        this.code = code;
        this.error = error;
        this.details = details;
    }
    OpenvSwitchException(Code code, String error) {
        this.code = code;
        this.error = error;
    }

    /**
     * Get the error code.
     * @return Error code in String.
     */
    public Code getCode() {
        return code;
    }

    /**
     * Get the error.
     * @return Error in String
     */
    public String getError() {
        return error;
    }
    /**
     * Get the error details
     * @return Error details in String.
     */
    public String getDetails() {
        return details;
    }

    /**
     * Get the message to be displayed in the log.
     * @return The message to be displayed in the log.
     */
    @Override
    public String getMessage() {
        String message = getCodeMessage(code);
        if (error == null && details == null)
            return message;
        if (details == null)
            return message + error;
        return message + error + " details = " + details;
    }

    /**
     * @see Code#OVSERROR
     */
    public static class OVSDBException extends OpenvSwitchException {
        public OVSDBException() {
            super(Code.OVSERROR);
        }
        public OVSDBException(String error) {
            super(Code.OVSERROR, error);
        }
        public OVSDBException(String error, String details) {
            super(Code.OVSERROR, error, details);
        }
    }
    /**
     * @see Code#NOTFOUND
     */
    public static class NotFoundException extends OpenvSwitchException {
        public NotFoundException() {
            super(Code.NOTFOUND);
        }
        public NotFoundException(String error) {
            super(Code.NOTFOUND, error);
        }
        public NotFoundException(String error, String details) {
            super(Code.NOTFOUND, error, details);
        }
    }
}