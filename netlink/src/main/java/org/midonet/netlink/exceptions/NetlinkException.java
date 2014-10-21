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

public class NetlinkException extends Exception {

    public enum ErrorCode {
        E_OK("No error"),
        EPERM("Operation not permitted"),
        ENOENT("No such file or directory"),
        ESRCH("No such process"),
        EINTR("Interrupted system call"),
        EIO("I/O error"),
        ENXIO("No such device or address"),
        E2BIG("Argument list too long"),
        ENOEXEC("Exec format error"),
        EBADF("Bad file number"),
        ECHILD("No child processes"),
        EAGAIN("Try again"),
        ENOMEM("Out of memory"),
        EACCES("Permission denied"),
        EFAULT("Bad address"),
        ENOTBLK("Block device required"),
        EBUSY("Device or resource busy"),
        EEXIST("File exists"),
        EXDEV("Cross-device link"),
        ENODEV("No such device"),
        ENOTDIR("Not a directory"),
        EISDIR("Is a directory"),
        EINVAL("Invalid argument"),
        ENFILE("File table overflow"),
        EMFILE("Too many open files"),
        ENOTTY("Not a typewriter"),
        ETXTBSY("Text file busy"),
        EFBIG("File too large"),
        ENOSPC("No space left on device"),
        ESPIPE("Illegal seek"),
        EROFS("Read-only file system"),
        EMLINK("Too many links"),
        EPIPE("Broken pipe"),
        EDOM("Math argument out of domain of func"),
        ERANGE("Math result not representable"),
        E_MAX(""),
        E_NOT_INITIALIZED("Not initialized exception"),
        ETIMEOUT("Request timeout");

        String message;
        private ErrorCode(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    int errorCode;

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
        ErrorCode[] values = ErrorCode.values();
        if ( errorCode > 0 && errorCode < values.length)
            return values[errorCode];

        return null;
    }

    public int getErrorCode() {
        return errorCode;
    }

    private static String format(int errorCode, String message) {
        if (message != null)
            return "[" + errorCode + "] " + message;

        return "error code: " + errorCode;
    }
}

