package branchsync.infrastructure;
import branchsync.domain.Models.State;
import java.io.*;
import java.nio.file.*;

public final class SnapshotStore {
    private final Path file;
    public SnapshotStore(Path file)throws IOException{this.file=file;Files.createDirectories(file.toAbsolutePath().getParent());}
    public State load()throws IOException{
        if(!Files.exists(file))return null;
        try(ObjectInputStream in=new ObjectInputStream(Files.newInputStream(file))){return (State)in.readObject();}
        catch(ClassNotFoundException e){throw new IOException("Unsupported state version",e);}
    }
    public void save(State state)throws IOException{
        Path tmp=file.resolveSibling(file.getFileName()+".tmp");
        try(FileOutputStream f=new FileOutputStream(tmp.toFile());ObjectOutputStream o=new ObjectOutputStream(f)){o.writeObject(state);o.flush();f.getFD().sync();}
        try{Files.move(tmp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
    }
}
