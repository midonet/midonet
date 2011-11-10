package com.midokura.midolman.mgmt.data.dto;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class Admin extends ResourceDao {
    private String init = null;

    /**
     * @return the init
     */
    public String getInit() {
        return init;
    }

    /**
     * @param init
     *            the init to set
     */
    public void setInit(String init) {
        this.init = init;
    }

}
