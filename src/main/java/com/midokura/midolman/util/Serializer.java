/*
 * @(#)Serializer        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.util;

import java.io.IOException;

/**
 * Interface for serializers.
 * @version        1.6 10 Sept 2011
 * @author         Ryu Ishimoto
 */
public interface Serializer<T> {

	/**
	 * Convert an object of type T to an array of bytes.
	 * @param obj
	 * @return
	 * @throws IOException
	 */
	public byte[] objToBytes(T obj) throws IOException;
	
	/**
	 * Convert an array of bytes to an object of type T.
	 * @param data Array of bytes
	 * @param clazz  Class to convert the bytes to
	 * @return  An object of class T.
	 * @throws IOException  IO error.
	 */
	public T bytesToObj(byte[] data, Class<T> clazz)
		throws IOException;
	
}
