package branchsync.cluster;
import java.rmi.*;
import branchsync.domain.Models.*;
public interface BankRemote extends Remote {
    NodeStatus status(String secret)throws RemoteException;
    Result submit(Command command,long clock,String secret)throws RemoteException;
    void replicate(State state,long clock,int sender,String secret)throws RemoteException;
    int election(int candidate,long clock,String secret)throws RemoteException;
    void coordinator(int leader,long clock,String secret)throws RemoteException;
}
