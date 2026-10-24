package branchsync.cluster;
import java.io.*;
import java.net.*;
import java.rmi.server.*;
/** Internal cluster traffic is deliberately bound to IPv4 loopback. */
public final class LoopbackSockets implements RMIClientSocketFactory,RMIServerSocketFactory,Serializable {
    private static final long serialVersionUID=1L;
    public Socket createSocket(String host,int port)throws IOException{return new Socket(InetAddress.getByName("127.0.0.1"),port);}
    public ServerSocket createServerSocket(int port)throws IOException{return new ServerSocket(port,50,InetAddress.getByName("127.0.0.1"));}
    public boolean equals(Object o){return o instanceof LoopbackSockets;}
    public int hashCode(){return 71923;}
}
