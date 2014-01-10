## Midonet Code Conventions

This document contains the code conventions that this codebase must abide by.

A convention is a weak rule pertaining to style, structure or content of every
source file, for which there can be sensible exceptions. The codebase should
eventually converge to a state where all of the conventions are followed.

### Source files header

The mandatory header of every source file must be written as:

    /*
     * Copyright (c) {yyyy} Midokura Europe SARL, All Rights Reserved.
     */

### Future continuations

In the fast path, when scheduling a continuation on a future using any of the
combinator methods (`flatMap`, `map`, `recover`, et al), strongly consider
using the CallingThreadExecutionContext (see details in the source file). Do
this either by importing it into the current scope or by explicitly passing it
to the combinator:

    implicit val ex = ExecutionContext.callingThread

    // Both continuations will execute in the context of the thread completing
    // the first future
    f map { /* ... */ } recover { /* ... */ }

or

    // The second continuation is executed in the context of the thread
    // completing the first continuation
    f map { /* ... */ }.recover { /* ... */ }(ExecutionContext.callingThread)