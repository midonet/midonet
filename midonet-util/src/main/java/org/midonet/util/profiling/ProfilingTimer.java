/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.util.profiling;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
//TODO(rossella) make this class smarter, maybe use AspectJ to inject the time
// estimation of every method in a class without having to call explicitly start() end()
public class ProfilingTimer {

    Logger log;
    Map<String, Long> methodToStartTimeMap = new HashMap<String, Long>();

    public ProfilingTimer(Logger log) {
        this.log = log;
    }

    public void start(){
        long start = System.currentTimeMillis();
        // The element in pos 2 of the stack trace is the method that called this one.
        // This is not always true see
        // http://docs.oracle.com/javase/6/docs/api/java/lang/Thread.html#getStackTrace%28%29
        // "Some virtual machines may, under some circumstances, omit one or more stack
        // frames from the stack trace. In the extreme case, a virtual machine
        // that has no stack trace information concerning this thread is permitted
        // to return a zero-length array from this method."
        String methodName = Thread.currentThread().getStackTrace()[2].toString()
                            //this is for taking away the line number
                            .split(":")[0];
        methodToStartTimeMap.put(methodName, start);
    }

    public void end(){
        String methodNameWithLine = Thread.currentThread().getStackTrace()[2].toString();
        String methodName = methodNameWithLine.split(":")[0];
        if(methodToStartTimeMap.containsKey(methodName)){
            long end = System.currentTimeMillis();
            // it's useful to print the line because in the IDE you can navigate to
            // it directly
            log.trace("{} execution time: {}", new Object[]{
                methodNameWithLine, (end-methodToStartTimeMap.get(methodName))});
            methodToStartTimeMap.remove(methodName);
        }
    }

}
