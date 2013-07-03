/*
 * Copyright 2011 Midokura KK
 * Copyright 2012-2013 Midokura PTE LTD.
 */
package org.midonet.api.filter;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;
import javax.xml.bind.annotation.XmlRootElement;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.midonet.api.ResourceUriBuilder;

import org.codehaus.jackson.annotate.JsonSubTypes;
import org.codehaus.jackson.annotate.JsonTypeInfo;

/**
 * Class representing rule.
 */
@XmlRootElement
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY,
        property = "type")
@JsonSubTypes({
        @JsonSubTypes.Type(value = AcceptRule.class, name = RuleType.Accept),
        @JsonSubTypes.Type(value = DropRule.class, name = RuleType.Drop),
        @JsonSubTypes.Type(value = ForwardDnatRule.class, name = RuleType.DNAT),
        @JsonSubTypes.Type(value = ForwardSnatRule.class,
                name = RuleType.SNAT),
        @JsonSubTypes.Type(value = JumpRule.class, name = RuleType.Jump),
        @JsonSubTypes.Type(value = RejectRule.class, name = RuleType.Reject),
        @JsonSubTypes.Type(value = ReturnRule.class, name = RuleType.Return),
        @JsonSubTypes.Type(value = ReverseDnatRule.class,
                name = RuleType.RevDNAT),
        @JsonSubTypes.Type(value = ReverseSnatRule.class,
                name = RuleType.RevSNAT)})
public abstract class Rule extends Condition {

    private UUID id;
    private UUID chainId;

    @Min(1)
    private int position = 1;

    private Map<String, String> properties = new HashMap<String, String>();

    public Rule() { super(); }

    public Rule(org.midonet.cluster.data.Rule data) {
        this.id = UUID.fromString(data.getId().toString());
        this.chainId = data.getChainId();
        this.position = data.getPosition();
        this.properties = data.getProperties();
        setFromCondition(data.getCondition());
    }

    @NotNull
    public abstract String getType();

    public abstract org.midonet.cluster.data.Rule toData();

    protected void setData(org.midonet.cluster.data.Rule data) {
        data.setId(id);
        data.setChainId(chainId);
        data.setPosition(position);
        data.setProperties(properties);
        data.setCondition(makeCondition());
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getChainId() {
        return chainId;
    }

    public void setChainId(UUID chainId) {
        this.chainId = chainId;
    }

    public int getPosition() {
        return position;
    }

    public void setPosition(int position) {
        this.position = position;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }

    /**
     * @return the self URI
     */
    @Override
    public URI getUri() {
        if (getBaseUri() != null && id != null) {
            return ResourceUriBuilder.getRule(getBaseUri(), id);
        } else {
            return null;
        }
    }
}
