package branchsync.security;
import branchsync.domain.Models.User;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Opaque server-side sessions; no JWT or credentials stored in browser storage. */
public final class Sessions {
    public static final long IDLE=15*60*1000L,MAX=8*60*60*1000L;
    public static final class Session {
        public final String token,csrf,user;public final int version;public final long created;
        public volatile long touched;
        Session(User u){token=random();csrf=random();user=u.id();version=u.version();created=touched=System.currentTimeMillis();}
    }
    private final ConcurrentMap<String,Session> sessions=new ConcurrentHashMap<>();
    private final ConcurrentMap<String,ArrayDeque<Long>> attempts=new ConcurrentHashMap<>();
    private static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public Session create(User u){purge();Session s=new Session(u);sessions.put(s.token,s);return s;}
    public Session get(String token){Session s=token==null?null:sessions.get(token);long now=System.currentTimeMillis();if(s!=null&&(now-s.touched>IDLE||now-s.created>MAX)){sessions.remove(token);s=null;}if(s!=null)s.touched=now;return s;}
    public void remove(String token){if(token!=null)sessions.remove(token);}
    private void purge(){long now=System.currentTimeMillis();sessions.values().removeIf(s->now-s.touched>IDLE||now-s.created>MAX);}
    public boolean allowLogin(String key){ArrayDeque<Long> q=attempts.computeIfAbsent(key,k->new ArrayDeque<>());synchronized(q){long now=System.currentTimeMillis();q.removeIf(t->now-t>60_000);if(q.size()>=5)return false;q.add(now);return true;}}
    public void loginSuccess(String key){attempts.remove(key);}
}
