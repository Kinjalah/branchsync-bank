package branchsync.infrastructure;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** All process paths are relative to the project root; launcher changes into it. */
public final class Config {
    private Config() {}
    public static final int PORT=Integer.parseInt(System.getenv().getOrDefault("BRANCHSYNC_PORT","8090"));
    public static final Path DATA=Path.of(System.getenv().getOrDefault("BRANCHSYNC_DATA","runtime"));
    public static String secret()throws Exception{
        Path p=DATA.resolve("cluster.key");if(!Files.exists(p))throw new IllegalStateException("Run setup first: java -cp out branchsync.Setup");
        return Files.readString(p,StandardCharsets.UTF_8).trim();
    }
    public static void require(String provided,String expected){
        if(provided==null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8)))throw new SecurityException("Internal cluster authentication failed");
    }
}
