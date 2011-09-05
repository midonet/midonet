/*
 * @(#)Router        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import java.util.UUID;

import javax.xml.bind.annotation.XmlRootElement;

/**
 * Class representing Virtual Router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
@XmlRootElement
public class Router {

	private UUID id = null;
	private String name = null;
	
	public String getName() {
		return name;
	}
	
	public void setName(String router_name) {
		name = router_name;
	}
	
	public UUID getId() {
		return id;
	}
	
	public void setId(UUID router_id) {
		id = router_id;
	}
}
