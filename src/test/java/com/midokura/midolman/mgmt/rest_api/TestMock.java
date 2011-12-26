package com.midokura.midolman.mgmt.rest_api;

import org.junit.Test;

import java.util.LinkedList;
import java.util.List;

import static org.mockito.Mockito.*;


/**
 * Author: Tomoe Sugihara <tomoe@midokura.com>
 * Date: 12/26/11
 * Time: 12:27 PM
 */
public class TestMock {
    
    @Test
    public void testIka(){
        LinkedList mockedList = mock(LinkedList.class);

        mockedList.add(0);
        mockedList.add(1);
        mockedList.get(0);
        
        verify(mockedList).get(0);

        System.out.println(mockedList.get(0));
        System.out.println(mockedList.get(1));
        
        
       
        


    }
    
    
}
