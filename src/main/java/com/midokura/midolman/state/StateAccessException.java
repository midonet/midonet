package com.midokura.midolman.state;

public class StateAccessException extends Exception {

	private static final long serialVersionUID = 1L;

	/**
	 * Default constructor
	 */
	public StateAccessException() {
		super();
	}
	
	public StateAccessException(String message) {
		super(message);
	}
	
	public StateAccessException(String message, Throwable cause) {
		super(message, cause);
	}	
}
