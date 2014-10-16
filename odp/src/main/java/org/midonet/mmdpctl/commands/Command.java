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
package org.midonet.mmdpctl.commands;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.midonet.odp.protos.OvsDatapathConnection;


/**
 * This base class is in charge of creating the Future that will receive the command result.
 * It's using a simple CachedThreadPool to launch the job.
 * @param <T>
 */
public abstract class Command<T> {

    /**
     * Method that runs the Callable.
     * @param task
     * @return
     */
    protected Future<T> run(Callable<T> task) {
        ExecutorService executorService = Executors.newCachedThreadPool();
        Future<T> result = executorService.submit(task);
        executorService.shutdown();
        return result;
    }

    /**
     * Each Command implementation needs to provide its own Callable to execute, and use the 'run' method in the base
     * class.
     * @return
     */
    public abstract Future<T> execute(OvsDatapathConnection connection);

}
