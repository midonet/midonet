package org.midonet.midolman.state;

public class TunnelZoneExistsException extends StateAccessException {

    private static final long serialVersionUID = 1L;

    public TunnelZoneExistsException() {
        super();
    }

    public TunnelZoneExistsException(String message) {
        super(message);
    }

    public TunnelZoneExistsException(String message, Throwable cause) {
        super(message, cause);
    }
}
