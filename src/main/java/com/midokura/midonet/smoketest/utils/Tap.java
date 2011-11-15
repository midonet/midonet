/*
* Copyright 2011 Midokura Europe SARL
*/

package com.midokura.midonet.smoketest.utils;

import java.math.BigInteger;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Structure;
import com.sun.jna.Union;

public class Tap {
	
	/* if.h */
    public static final int IFNAMSIZ = 16;
    
    /* if_tun.h */
    public static final int TUNSETIFF = 0x400454ca;
    public static final int TUNSETPERSIST = 0x400454cb;
    
    public static final int TUNSETOWNER = 0x400454cc;
    public static final int TUNSETGROUP = 0x400454ce;
    
    public static final int IFF_TUN = 0x0001;
    public static final int IFF_TAP = 0x0002;
    public static final int IFF_NO_PI = 0x1000;
    
    /* ioctls.h */
    public static final int SIOCGIFHWADDR = 0x8927;		/* Get hardware address		*/
    public static final int SIOCSIFHWADDR =	0x8924;		/* set hardware address 	*/
    
    /* fcntl.h */
    public static final int O_RDWR = 2;

    public static class InterfaceReq extends Structure{
    	
        public InterfaceReqName ifr_ifrn;
        public InterfaceReqParam ifr_ifru;
        public static class InterfaceReqByRef extends InterfaceReq implements Structure.ByReference { }
        
        public static class InterfaceReqName extends Union {
            public byte[] ifrn_name = new byte[IFNAMSIZ];
            public static class ByReference extends Union implements Structure.ByReference {
            }
        }

        public static class InterfaceReqParam extends Union {
            public SockAddr ifru_hwaddr;
            public SockAddr ifru_addr;
            public SockAddr ifru_dstaddr;
            public SockAddr ifru_broadaddr;
            public SockAddr ifru_netmask;
        	public short ifru_flags;
            public int ifru_ivalue;
            public int ifru_mtu;
            public byte[] ifru_slave = new byte[IFNAMSIZ];
            public byte[] ifru_newname = new byte[IFNAMSIZ];
            public static class ByReference extends Union implements Structure.ByReference {
            }
        }
        
        public static class SockAddr extends Structure {
        	public short sa_family_t;
        	public byte[] sa_data = new byte[14];
        }
    }
    
    public class CLibrary {
        public native int put(String s);
        public CLibrary()
        {
        System.loadLibrary("c");   /* Note lowercase of classname! */
        }
    }
    
    public static int openTap(String tapName)
    {
    	// check if the the parameter is valid
        if(tapName.length() > IFNAMSIZ)
        {
        	throw new RuntimeException("Tap name too long!");
        }
        
    	String tunPath = "/dev/net/tun";
    	int fd = TestLibrary.INSTANCE.open(tunPath, O_RDWR);
    	if(fd < 0)
    		throw new RuntimeException("Problem in opening /dev/net/tun");
        InterfaceReq interfaceReq = new InterfaceReq();
        
        interfaceReq.ifr_ifru.setType("ifru_flags");
        interfaceReq.ifr_ifrn.setType("ifrn_name");
        interfaceReq.ifr_ifru.ifru_flags = IFF_TAP | IFF_NO_PI;

        if(!tapName.isEmpty())
        	interfaceReq.ifr_ifrn.ifrn_name = tapName.getBytes();
        int result = TestLibrary.INSTANCE.ioctl(fd, TUNSETIFF, interfaceReq);
        
        // check if ioctl has been successful
        if(result < 0)
        	throw new RuntimeException("Problem in opening tap, ioctl failed");
        
        return result;
        
        /* ALTERNATIVE
      	NativeLibrary unix = NativeLibrary.getInstance("c");
    	Function ioctl = unix.getFunction("ioctl");
    	
    	int res2 = ioctl.invokeInt(new Object[]{fd, 0x400454ca, interfaceReq});  
         */
    }
    public static int openPersistentTapSetOwner(String tapName, int owner)
    {
    	return openPersistentTap(tapName, owner, -1, "");
    }
    
    public static int openPersistentTapSetGroup(String tapName, int group)
    {
    	return openPersistentTap(tapName, -1, group, "");
    }
    
    public static int openPersistentTap(String tapName)
    {
    	return openPersistentTap(tapName, -1, -1, "");
    }
    
    public static int openPersistentTap(String tapName, String hwAddr)
    {
    	return openPersistentTap(tapName, -1, -1, hwAddr);
    }
    
    public static int openPersistentTap(String tapName, int owner, int group, String hwAddr)
    {
    	int fd = openTap(tapName);
    	    	
    	/* change owner */
    	if( owner >= 0 )
    		TestLibrary.INSTANCE.ioctl(fd, TUNSETOWNER, owner);
    	
    	/* change group */
    	if( group >= 0 )
    		TestLibrary.INSTANCE.ioctl(fd, TUNSETGROUP, group);
    	
    	/* make it persistent */
    	int res = TestLibrary.INSTANCE.ioctl(fd, TUNSETPERSIST, 1);
    	
    	if(res < 0)
    		throw new RuntimeException("ioctl failed in making the tap persistent!");
    	
    	/* change hw address */
    	if( !hwAddr.isEmpty())
    	{
    		setHwAddress(fd, tapName, hwAddr);
    	}
    	
    	TestLibrary.INSTANCE.close(fd);
    	
    	return fd;
    }
    
    public static void setHwAddress(int fd, String tapName, String hwAddress)
    {
    	
    	if( fd < 0 | tapName.isEmpty() | hwAddress.isEmpty())
    		throw new RuntimeException("Invalid Parameter!");
    		
    	/* Prepare structure ifr_ifru */
    	
        InterfaceReq interfaceReq = new InterfaceReq();      
        interfaceReq.ifr_ifru.setType("ifru_hwaddr");
        interfaceReq.ifr_ifrn.setType("ifrn_name");
        
        /*  TapName */
        interfaceReq.ifr_ifrn.ifrn_name = tapName.getBytes();
        
        /* HW address */
        String[] bytes = hwAddress.split(":");
       // byte[] parsed = new byte[bytes.length];
        if(bytes.length != interfaceReq.ifr_ifru.ifru_hwaddr.sa_data.length)
        	throw new RuntimeException("Invalid MAC address!");
        
        for (int x = 0; x < bytes.length; x++)
        {
            BigInteger temp = new BigInteger(bytes[x], 16);
            byte[] raw = temp.toByteArray();
            interfaceReq.ifr_ifru.ifru_hwaddr.sa_data[x] = raw[raw.length - 1];
        }
        
        /* copy hw address family */
        InterfaceReq hwStruct = new InterfaceReq();
        getHwAddress(fd, tapName, hwStruct);
        interfaceReq.ifr_ifru.ifru_hwaddr.sa_family_t = hwStruct.ifr_ifru.ifru_hwaddr.sa_family_t;
        
    	/* change MAC */
    	int res = TestLibrary.INSTANCE.ioctl(fd, SIOCSIFHWADDR, interfaceReq);
    	if(res < 0)
    		throw new RuntimeException("ioctl failed in changing the MAC address!");
    }
    
    public static void getHwAddress(int fd, String tapName, InterfaceReq hwStruct)
    {
        InterfaceReq interfaceReq = new InterfaceReq();      
        interfaceReq.ifr_ifru.setType("ifru_hwaddr");
        interfaceReq.ifr_ifrn.setType("ifrn_name");
        
        /* clear struct */
        hwStruct.clear();
        
        /*  TapName */
        interfaceReq.ifr_ifrn.ifrn_name = tapName.getBytes();
        
        int res = TestLibrary.INSTANCE.ioctl(fd, SIOCGIFHWADDR, hwStruct);
    	if(res < 0)
    		throw new RuntimeException("ioctl failed in getting the MAC address!");
        
    }
    
    public static void destroyPersistentTap(int fd)
    {
    	int res = TestLibrary.INSTANCE.ioctl(fd, TUNSETPERSIST, 0);
    	if(res < 0)
    		throw new RuntimeException("ioctl failed in destroying the tap!");
    	
    }

    public interface TestLibrary extends Library {
        TestLibrary INSTANCE = (TestLibrary)
                Native.loadLibrary("c", TestLibrary.class);

        int ioctl(int fd, int code, InterfaceReq req);
        int ioctl(int fd, int code, int owner);
        
        int open(String name, int flags);
        int close(int fd);
        int fcntl(int fd, int cmd);
    }
    

}
