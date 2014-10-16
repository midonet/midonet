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
package org.midonet.midolman.tools;

/**
 * Interface for mm-ctl command result.
 */
public class MmCtlResult {

    private final int exitCode;
    private final String message;

    public MmCtlResult(int exitCode, String message) {
        this.exitCode = exitCode;
        this.message = message;
    }

    /**
     * The exit code from the command
     */
    public int getExitCode() {
        return this.exitCode;
    }

    /**
     * True if the command was a success.
     */
    public boolean isSuccess() {
        return getExitCode() == 0;
    }

    /**
     * Message from the command.
     */
    public String getMessage() {
        return this.message;
    }
}
