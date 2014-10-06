package org.midonet.api;

import java.net.URI;
import javax.xml.bind.annotation.XmlTransient;

import com.google.protobuf.MessageOrBuilder;

import org.midonet.cluster.data.ZoomObject;


public abstract class RelativeUriResource extends ZoomObject {

    private URI parentUri = null;

    /**
     * Default constructor
     */
    public RelativeUriResource() {
    }

    /**
     * Protobuf-conversion constructor.
     */
    public RelativeUriResource(MessageOrBuilder proto) {
        super(proto);
    }

    /**
     * @return the parentUri
     */
    @XmlTransient
    public URI getParentUri() {
        return parentUri;
    }

    /**
     * @param parentUri
     *            the parentUri to set
     */
    public void setParentUri(URI parentUri) {
        this.parentUri = parentUri;
    }

    /**
     * @return URI of the resource.
     */
    public abstract URI getUri();
}
