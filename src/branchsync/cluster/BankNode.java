package branchsync.cluster;

import branchsync.domain.Models.*;
import branchsync.application.BankingService;
import branchsync.infrastructure.*;
import java.io.*;
import java.nio.file.*;
import java.rmi.*;
import java.rmi.registry.*;
import java.rmi.server.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Teaching implementation: fixed three-node membership, crash/restart on a reliable localhost network.
 * Bully election is NOT consensus. See docs/DESIGN.md for explicit failure boundaries.
 */
public final class BankNode extends UnicastRemoteObject implements BankRemote {
    private static final long serialVersionUID = 2L;
    private final int id;
    private final SnapshotStore store;
    private final String secret;
    private final Object stateLock=new Object();
    // Serialize leader writes and incoming replica installations without holding the state lock over RMI.
    private final Object writeLock=new Object();
    private final Deque<Event> events=new ArrayDeque<>();
    private State state;
    private long clock;
    private volatile boolean ready;
    private volatile int leader;
    private final ScheduledExecutorService monitor=Executors.newSingleThreadScheduledExecutor();
    private volatile boolean firstMonitor=true;

    public BankNode(int id,Path directory)throws Exception {
        super(5100+id,new LoopbackSockets(),new LoopbackSockets());
        this.id=id;this.secret=Config.secret();this.store=new SnapshotStore(directory.resolve("state.bin"));
        state=store.load();if(state==null)throw new IOException("Seed snapshot missing; run Setup first");
        clock=state.transactions.stream().mapToLong(Transaction::clock).max().orElse(0);
        event("BOOT node "+id+"; persisted sequence "+state.sequence+"; recovering");
    }
    public static BankRemote peer(int n)throws RemoteException,NotBoundException {
        return (BankRemote)LocateRegistry.getRegistry("127.0.0.1",5000+n,new LoopbackSockets()).lookup("Bank");
    }
    private long tick(long received) {
        synchronized(stateLock) { clock=Math.max(clock,received)+1; return clock; }
    }
    private void event(String message) {
        synchronized(stateLock) {
            clock++; events.addLast(new Event(clock,message));
            while(events.size()>100) events.removeFirst();
            System.out.println("[Node "+id+" | L="+clock+"] "+message);
        }
    }
    private void save(State next)throws IOException {store.save(next);}
    private NodeStatus local(){synchronized(stateLock){return new NodeStatus(id,ready,leader,clock,state.copy(),new ArrayList<>(events));}}
    private void auth(String key){Config.require(key,secret);}
    @Override public NodeStatus status(String key) {
        auth(key); synchronized(stateLock) { return new NodeStatus(id,ready,leader,clock,state.copy(),new ArrayList<>(events)); }
    }
    @Override public int election(int candidate,long sent,String key) {
        auth(key);tick(sent); if(ready && id>candidate) { event("ELECTION from "+candidate+" -> OK (higher ID)"); return id; }
        return 0;
    }
    @Override public void coordinator(int elected,long sent,String key) {
        auth(key);tick(sent);
        if(leader!=elected) { leader=elected; event("COORDINATOR announced: Node "+elected); }
    }
    public void startMonitor() { monitor.scheduleWithFixedDelay(this::heartbeat,2500,1200,TimeUnit.MILLISECONDS); }
    private void heartbeat() {
        try {
            Map<Integer,NodeStatus> alive=new TreeMap<>();
            for(int n=1;n<=3;n++) if(n!=id) try { alive.put(n,peer(n).status(secret)); } catch(Exception ignored) {}
            if(!ready) {
                // Do not publish readiness until the most advanced reachable replica is installed.
                NodeStatus best=alive.values().stream().filter(NodeStatus::ready)
                    .max(Comparator.comparingLong(s->s.state().sequence)).orElse(null);
                if(best!=null) {
                    synchronized(writeLock) {
                        synchronized(stateLock) {
                            if(best.state().sequence>=state.sequence) { State copy=best.state().copy(); save(copy); state=copy; }
                            else { event("RECOVERY blocked: local state newer than active cluster; manual inspection required"); return; }
                        }
                    }
                    tick(best.clock()); event("RECOVERY synchronized through transaction "+best.state().sequence);
                    ready=true;
                } else {
                    // Bootstrap only when a majority of processes is reachable; choose most recent local state.
                    if(alive.size()<1) { if(firstMonitor) event("WAITING for another node to bootstrap"); firstMonitor=false; return; }
                    NodeStatus latest=alive.values().stream().max(Comparator.comparingLong(s->s.state().sequence)).orElseThrow();
                    if(latest.state().sequence>local().state().sequence) {
                        synchronized(writeLock) { synchronized(stateLock) { save(latest.state()); state=latest.state().copy(); } }
                        tick(latest.clock());
                    }
                    ready=true; event("BOOTSTRAP ready");
                }
            }
            int expected=id;
            for(NodeStatus s:alive.values()) if(s.ready()) expected=Math.max(expected,s.id());
            if(expected!=leader) {
                event("ELECTION started; previous leader "+leader+"; reachable highest ID "+expected);
                int elected=id;
                for(int n=id+1;n<=3;n++) if(alive.containsKey(n)) try {
                    elected=Math.max(elected,peer(n).election(id,tick(0),secret));
                } catch(Exception ignored) {}
                // Higher ready nodes announce themselves on their next heartbeat.
                if(elected==id) {
                    coordinator(id,tick(0),secret);
                    for(int n:alive.keySet()) try { peer(n).coordinator(id,tick(0),secret); } catch(Exception ignored) {}
                } else leader=elected;
            }
            // Repair any follower that missed the last replication in the controlled failure model.
            if(leader==id) {
                synchronized(writeLock) {
                    State snapshot=local().state();
                    for(NodeStatus s:alive.values()) if(s.ready() && s.state().sequence<snapshot.sequence) try {
                        peer(s.id()).replicate(snapshot,tick(0),id,secret);
                    } catch(Exception e) { event("CATCH-UP pending for Node "+s.id()); }
                }
            }
        } catch(Exception e) { event("MONITOR: "+e.getClass().getSimpleName()+" "+e.getMessage()); }
    }
    @Override public void replicate(State incoming,long sent,int sender,String key) throws RemoteException {
        auth(key);tick(sent);
        synchronized(writeLock) {
            synchronized(stateLock) {
                if(!ready) throw new RemoteException("Replica is recovering");
                if(sender!=leader) throw new RemoteException("Sender is not this node's current leader");
                if(incoming.sequence<state.sequence) throw new RemoteException("Refusing stale replica");
                if(incoming.sequence==state.sequence) return;
                try { State copy=incoming.copy(); save(copy); state=copy; }
                catch(IOException e) { ready=false; throw new RemoteException("Persistence failed",e); }
            }
            event("REPLICATE received from Node "+sender+"; sequence "+incoming.sequence);
        }
    }
    @Override public Result submit(Command c,long sent,String key)throws RemoteException {
        auth(key);tick(sent);
        if(!ready)throw new RemoteException("Node recovering; retry the same request ID shortly");
        if(leader==0)throw new RemoteException("Election in progress; wait for a leader");
        if(leader!=id){
            event("FORWARD "+c.action()+" to leader "+leader);
            try{return peer(leader).submit(c,tick(0),secret);}catch(NotBoundException e){throw new RemoteException("Leader unavailable",e);}
        }
        synchronized(writeLock){
            if(leader!=id||!ready)throw new RemoteException("Leader changed; wait for election");
            State current=local().state();
            if(c==null||c.id()==null||!c.id().matches("[A-Za-z0-9-]{8,80}"))return new Result(false,"Valid request ID required",current.sequence,Map.of());
            if(current.results.containsKey(c.id())){
                if(!current.commands.get(c.id()).equals(c))return new Result(false,"Request ID already used with different details",current.sequence,Map.of());
                event("IDEMPOTENCY duplicate ignored: "+c.action());return current.results.get(c.id());
            }
            List<Integer> followers=new ArrayList<>();
            for(int n=1;n<=3;n++)if(n!=id)try{NodeStatus p=peer(n).status(secret);if(p.ready()&&p.leader()==id&&p.state().sequence==current.sequence)followers.add(n);}catch(Exception ignored){}
            if(followers.isEmpty())throw new RemoteException("Writes paused: a synchronized follower is required");
            State next=current.copy();next.sequence++;Result result;
            try{result=BankingService.apply(next,c,tick(0),id);}catch(IllegalArgumentException|ArithmeticException e){event("REJECT "+c.action()+": "+e.getMessage());return new Result(false,e.getMessage(),current.sequence,Map.of());}
            next.commands.put(c.id(),c);next.results.put(c.id(),result);
            synchronized(stateLock){try{save(next);state=next;}catch(IOException e){ready=false;throw new RemoteException("Storage unavailable",e);}}
            int ack=0;for(int n:followers)try{peer(n).replicate(next,tick(0),id,secret);ack++;}catch(Exception e){event("REPLICA ACK missing: Node "+n);}
            event("APPLIED #"+next.sequence+" "+c.action()+" | follower ACKs="+ack);
            if(ack==0)throw new RemoteException("OUTCOME UNCERTAIN: request stored on leader without replica ACK. Retain request ID and inspect history.");
            return result;
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1) throw new IllegalArgumentException("Usage: BankNode <1|2|3>");
        int id=Integer.parseInt(args[0]); if(id<1 || id>3) throw new IllegalArgumentException("Node must be 1, 2 or 3");
        System.setProperty("java.rmi.server.hostname","127.0.0.1");
        System.setProperty("sun.rmi.transport.tcp.responseTimeout","1800");
        System.setProperty("sun.rmi.transport.connectionTimeout","1800");
        System.setProperty("sun.rmi.transport.tcp.handshakeTimeout","1800");
        BankNode node=new BankNode(id,Config.DATA.resolve("node"+id));
        LocateRegistry.createRegistry(5000+id,new LoopbackSockets(),new LoopbackSockets()).rebind("Bank",node);
        node.startMonitor();
        System.out.println("Registry="+(5000+id)+"; RMI="+(5100+id)+". Ctrl+C to stop this node.");
    }
}
