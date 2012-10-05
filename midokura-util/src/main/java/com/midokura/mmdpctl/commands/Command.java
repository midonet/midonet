package com.midokura.mmdpctl.commands;


import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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
    protected Future<T> run(Callable task) {
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
    public abstract Future<T> execute();

}
