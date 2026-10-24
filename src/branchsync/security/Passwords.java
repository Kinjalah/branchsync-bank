package branchsync.security;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.*;
import java.util.*;

public final class Passwords {
    private Passwords() {}
    public static String hash(String password) {
        byte[] salt=new byte[16]; new SecureRandom().nextBytes(salt);
        return "600000:"+Base64.getEncoder().encodeToString(salt)+":"+Base64.getEncoder().encodeToString(derive(password,salt,600000));
    }
    private static byte[] derive(String value,byte[] salt,int rounds) {
        PBEKeySpec spec=new PBEKeySpec(value.toCharArray(),salt,rounds,256);
        try{return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}finally{spec.clearPassword();}
    }
    public static boolean verify(String value,String stored) {
        try{String[] p=stored.split(":"); return MessageDigest.isEqual(Base64.getDecoder().decode(p[2]),derive(value,Base64.getDecoder().decode(p[1]),Integer.parseInt(p[0])));}
        catch(RuntimeException e){return false;}
    }
    public static void validate(String p) {
        if(p==null || p.length()<12 || p.length()>128 || !p.matches("(?s).*[A-Z].*") || !p.matches("(?s).*[a-z].*") || !p.matches("(?s).*[0-9].*"))
            throw new IllegalArgumentException("Use 12–128 characters with uppercase, lowercase and a number");
    }
}
