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
package org.midonet.netlink.exceptions;

import org.midonet.ErrorCode;

public class NetlinkException extends Exception {
    public final int errorCode;

    public static final int ERROR_SENDING_REQUEST = -1;
    public static final int GENERIC_IO_ERROR = -2;

    public NetlinkException(ErrorCode error, String message) {
        super(format(error.ordinal(), message));
        this.errorCode = error.ordinal();
    }

    public NetlinkException(ErrorCode error, Throwable cause) {
        super(format(error.ordinal(), null), cause);
        this.errorCode = error.ordinal();
    }

    public NetlinkException(ErrorCode error) {
        super(format(error.ordinal(), error.getMessage()));
        this.errorCode = error.ordinal();
    }

    public NetlinkException(int errorCode, String message) {
        super(format(errorCode, message));
        this.errorCode = errorCode;
    }

    public NetlinkException(int errorCode, String message, Throwable cause) {
        super(format(errorCode, message), cause);
        this.errorCode = errorCode;
    }

    public NetlinkException(int errorCode, Throwable cause) {
        super(format(errorCode, null), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCodeEnum() {
        try {
            return ErrorCode.values()[errorCode];
        } catch (ArrayIndexOutOfBoundsException e) {
            return ErrorCode.UNRECOGNIZED;
        }
    }

    @Override
    public Throwable fillInStackTrace() {
        return this;
    }

    private static String format(int errorCode, String message) {
        if (message != null)
            return "[" + errorCode + "] " + message;

        return "error code: " + errorCode;
    }
}

