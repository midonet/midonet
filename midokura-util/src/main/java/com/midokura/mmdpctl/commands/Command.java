package com.midokura.mmdpctl.commands;


import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public abstract class Command<T> {

    protected Future<T> run(Callable task) {
        ExecutorService executorService = Executors.newCachedThreadPool();
        Future<T> result = executorService.submit(task);
        executorService.shutdown();
        return result;
    }

    public abstract Future<T> execute();

}
