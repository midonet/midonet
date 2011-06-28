package com.midokura.midolman.state;

public interface StringEncodable {
    String encode();
    void decode(String str);
}
