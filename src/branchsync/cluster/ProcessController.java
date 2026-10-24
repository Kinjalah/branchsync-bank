package branchsync.cluster;
import branchsync.infrastructure.Config;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Owns only node processes created by this gateway; never kills arbitrary Java processes. */
public final class ProcessController implements AutoCloseable {
    private final Map<Integer,Process> processes=new ConcurrentHashMap<>();
    public synchronized void start(int id)throws Exception{
        if(id<1||id>3)throw new IllegalArgumentException("Node must be 1, 2 or 3");
        Process p=processes.get(id);if(p!=null&&p.isAlive())throw new IllegalArgumentException("Node is already running");
        try(ServerSocket probe=new ServerSocket()){probe.bind(new InetSocketAddress("127.0.0.1",5000+id));}catch(Exception e){throw new IllegalArgumentException("Node port "+(5000+id)+" is already in use. Stop the older BranchSync instance first.");}
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Path log=Config.DATA.resolve("logs/node"+id+".log");Files.createDirectories(log.getParent());
        Process child=new ProcessBuilder(java,"-cp",System.getProperty("java.class.path"),"branchsync.cluster.BankNode",Integer.toString(id)).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        processes.put(id,child);
    }
    public synchronized void stop(int id)throws Exception{
        Process p=processes.get(id);if(p==null||!p.isAlive())throw new IllegalArgumentException("Node is already stopped");
        p.destroy();if(!p.waitFor(3,TimeUnit.SECONDS)){p.destroyForcibly();p.waitFor(3,TimeUnit.SECONDS);}
    }
    public boolean running(int id){Process p=processes.get(id);return p!=null&&p.isAlive();}
    public void close(){for(Process p:processes.values())if(p.isAlive())p.destroy();}
}
