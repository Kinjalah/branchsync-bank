package branchsync;
import branchsync.domain.Models.*;
import branchsync.infrastructure.*;
import branchsync.security.Passwords;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.security.SecureRandom;
import java.util.*;

public final class Setup {
    public static synchronized void initialize()throws Exception{
        Files.createDirectories(Config.DATA);
        Path key=Config.DATA.resolve("cluster.key");
        if(!Files.exists(key)){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);Files.writeString(key,Base64.getEncoder().encodeToString(bytes),StandardCharsets.UTF_8,StandardOpenOption.CREATE_NEW);try{Files.setPosixFilePermissions(key,java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));}catch(UnsupportedOperationException ignored){}}
        SnapshotStore seedStore=new SnapshotStore(Config.DATA.resolve("seed.bin"));State seed=seedStore.load();
        if(seed==null){
            seed=new State();
            addUser(seed,"U1001","kinjal","Kinjal Heda","kinjal@example.test","9876500001","CUSTOMER","Kinjal@12345");
            addUser(seed,"U1002","aarav","Aarav Shah","aarav@example.test","9876500002","CUSTOMER","Aarav@123456");
            addUser(seed,"U2001","teller","Meera Kulkarni","teller@example.test","9876500003","TELLER","Teller@12345");
            addUser(seed,"U3001","admin","BranchSync Operations","admin@example.test","9876500004","ADMIN","Admin@123456");
            addAccount(seed,"10010001","U1001","Everyday Savings",12_500_000L,"7421");
            addAccount(seed,"10010002","U1001","Everyday Current",2_850_000L,"1864");
            addAccount(seed,"10020001","U1002","Everyday Savings",8_640_000L,"9306");
            seed.beneficiaries.put("B1001",new Beneficiary("B1001","U1001","10020001","Aarav Shah"));
            seedStore.save(seed);
        }
        for(int n=1;n<=3;n++){SnapshotStore store=new SnapshotStore(Config.DATA.resolve("node"+n).resolve("state.bin"));if(store.load()==null)store.save(seed.copy());}
        Files.createDirectories(Config.DATA.resolve("logs"));
    }
    private static void addUser(State s,String id,String username,String name,String email,String phone,String role,String password){s.users.put(id,new User(id,username,name,email,phone,role,Passwords.hash(password),"ACTIVE","VERIFIED",1));}
    private static void addAccount(State s,String id,String owner,String label,long balance,String card){s.accounts.put(id,new Account(id,owner,label,balance,"ACTIVE","ACTIVE",card,500_000_00L));s.transactions.add(new Transaction("OPEN-"+id,0,"opening-"+id,"OPENING_BALANCE","OPENING_FUNDS",id,balance,"Opening demo balance",owner,0,0,Instant.now().toString()));}
    public static void main(String[] args)throws Exception{initialize();System.out.println("BranchSync setup ready. Existing data is preserved.");}
}
