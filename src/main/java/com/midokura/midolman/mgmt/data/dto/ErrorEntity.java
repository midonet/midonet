package com.midokura.midolman.mgmt.data.dto;

import javax.xml.bind.annotation.XmlRootElement;


/**
 * Class representing error.
 * 
 * @version        1.6 11 Sept 2011
 * @author         Ryu Ishimoto
 */
@XmlRootElement
public class ErrorEntity {
	private String message = null;
	private int code = 0;
	
	/**
	 * @return the message
	 */
	public String getMessage() {
		return message;
	}
	/**
	 * @param message the message to set
	 */
	public void setMessage(String message) {
		this.message = message;
	}
	/**
	 * @return the code
	 */
	public int getCode() {
		return code;
	}
	/**
	 * @param code the code to set
	 */
	public void setCode(int code) {
		this.code = code;
	}
}
