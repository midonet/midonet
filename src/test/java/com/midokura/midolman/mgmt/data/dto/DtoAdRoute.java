package com.midokura.midolman.mgmt.data.dto;

import javax.xml.bind.annotation.XmlRootElement;
import java.util.UUID;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/28/11
 * Time: 5:54 PM
 */
@XmlRootElement
public class DtoAdRoute {

    private UUID id = null;

    String nwPrefix;
    int prefixLength;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getNwPrefix() {
        return nwPrefix;
    }

    public void setNwPrefix(String nwPrefix) {
        this.nwPrefix = nwPrefix;
    }

    public int getPrefixLength() {
        return prefixLength;
    }

    public void setPrefixLength(int prefixLength) {
        this.prefixLength = prefixLength;
    }
}
