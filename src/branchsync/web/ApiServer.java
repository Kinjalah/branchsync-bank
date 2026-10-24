package branchsync.web;
import branchsync.*;
import branchsync.domain.Models.*;
import branchsync.cluster.*;
import branchsync.infrastructure.*;
import branchsync.security.*;
import branchsync.security.Sessions.Session;
import branchsync.common.Json;
import com.sun.net.httpserver.*;
import java.io.*;
import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

public final class ApiServer {
    private final String secret;
    private final Sessions sessions=new Sessions();
    private final ProcessController nodes=new ProcessController();
    private static final class HttpFailure extends RuntimeException {final int code;HttpFailure(int c,String m){super(m);code=c;}}
    private ApiServer()throws Exception{secret=Config.secret();}
    private static void require(boolean ok,int code,String text){if(!ok)throw new HttpFailure(code,text);}
    private List<NodeStatus> statuses(){List<NodeStatus> list=new ArrayList<>();for(int n=1;n<=3;n++)try{list.add(BankNode.peer(n).status(secret));}catch(Exception ignored){}return list;}
    private State state(){List<NodeStatus> list=statuses();return list.stream().filter(NodeStatus::ready).max(Comparator.comparingLong(n->n.state().sequence)).orElseThrow(()->new HttpFailure(503,"Banking cluster is starting or recovering. Try again shortly.")).state();}
    private static Map<String,String> form(String raw){Map<String,String> f=new HashMap<>();if(raw==null)return f;for(String part:raw.split("&")){String[] p=part.split("=",2);if(p.length==2)f.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p[1],StandardCharsets.UTF_8));}return f;}
    private static Map<String,String> body(HttpExchange x)throws IOException{String ct=x.getRequestHeaders().getFirst("Content-Type");require(ct!=null&&ct.startsWith("application/x-www-form-urlencoded"),415,"Form-encoded request required");byte[] bytes=x.getRequestBody().readNBytes(16385);require(bytes.length<=16384,413,"Request too large");return form(new String(bytes,StandardCharsets.UTF_8));}
    private static String cookie(HttpExchange x){String raw=x.getRequestHeaders().getFirst("Cookie");if(raw!=null)for(String p:raw.split(";")){String[] pair=p.trim().split("=",2);if(pair.length==2&&pair[0].equals("bs_session"))return pair[1];}return null;}
    private Session session(HttpExchange x){Session s=sessions.get(cookie(x));require(s!=null,401,"Sign in to continue");return s;}
    private User authenticated(State state,Session s){User u=state.users.get(s.user);require(u!=null&&u.status().equals("ACTIVE")&&u.version()==s.version,401,"Your session has expired. Sign in again.");return u;}
    private static void csrf(HttpExchange x,Session s){String header=x.getRequestHeaders().getFirst("X-CSRF-Token");require(header!=null&&MessageDigest.isEqual(header.getBytes(StandardCharsets.UTF_8),s.csrf.getBytes(StandardCharsets.UTF_8)),403,"Request verification failed. Refresh the page and try again.");}
    private static void securityHeaders(HttpExchange x){var h=x.getResponseHeaders();h.set("X-Content-Type-Options","nosniff");h.set("X-Frame-Options","DENY");h.set("Referrer-Policy","no-referrer");h.set("Cache-Control","no-store");h.set("Content-Security-Policy","default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'");}
    private static void bytes(HttpExchange x,int code,String mime,byte[] data)throws IOException{securityHeaders(x);x.getResponseHeaders().set("Content-Type",mime);x.sendResponseHeaders(code,data.length);x.getResponseBody().write(data);x.close();}
    private static void json(HttpExchange x,int code,Object data)throws IOException{bytes(x,code,"application/json; charset=utf-8",Json.stringify(data).getBytes(StandardCharsets.UTF_8));}
    private void origin(HttpExchange x){String o=x.getRequestHeaders().getFirst("Origin");require(o==null||o.equals("http://localhost:"+Config.PORT)||o.equals("http://127.0.0.1:"+Config.PORT),403,"Origin not allowed");}
    private static int branch(Map<String,String> f){try{int n=Integer.parseInt(f.getOrDefault("branch","1"));require(n>=1&&n<=3,400,"Choose branch 1, 2 or 3");return n;}catch(NumberFormatException e){throw new HttpFailure(400,"Invalid branch");}}
    private Result submit(Command c,int branch)throws Exception{return BankNode.peer(branch).submit(c,0,secret);}
    private static String fingerprint(State s){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Json.canonical(s).getBytes(StandardCharsets.UTF_8))).substring(0,16);}catch(Exception e){throw new IllegalStateException(e);}}
    private void route(HttpExchange x)throws Exception{
        String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
        if(method.equals("POST"))origin(x);
        if(path.equals("/api/login")&&method.equals("POST")){
            Map<String,String> f=body(x);String username=f.getOrDefault("username","").trim().toLowerCase(Locale.ROOT),password=f.getOrDefault("password","");
            require(username.length()<=40&&password.length()<=128,400,"Invalid credentials");String key=x.getRemoteAddress().getAddress()+":"+username;
            State loginState=state();
            require(sessions.allowLogin(key),429,"Too many attempts. Wait one minute before trying again.");
            User u=loginState.users.values().stream().filter(a->a.username().equals(username)).findFirst().orElse(null);
            // A fixed dummy encoding keeps unknown-user failures on the PBKDF2 verification path.
            String encoded=u==null?"600000:AAAAAAAAAAAAAAAAAAAAAA==:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=":u.password();
            boolean verified=Passwords.verify(password,encoded);require(u!=null&&verified&&u.status().equals("ACTIVE"),401,"Username or password is incorrect, or access is suspended");
            sessions.loginSuccess(key);sessions.remove(cookie(x));Session s=sessions.create(u);
            x.getResponseHeaders().add("Set-Cookie","bs_session="+s.token+"; Path=/; HttpOnly; SameSite=Strict");json(x,200,Map.of("user",Views.user(u),"csrf",s.csrf));return;
        }
        if(path.equals("/api/register")&&method.equals("POST")){
            Map<String,String> f=body(x);require(sessions.allowLogin("register:"+x.getRemoteAddress().getAddress()),429,"Too many registrations; wait one minute");
            String password=f.remove("password");Passwords.validate(password);f.put("passwordHash",Passwords.hash(password));
            String request=f.remove("id");int b=branch(f);f.remove("branch");
            Result result=submit(new Command(request,"","REGISTER",f),b);json(x,result.ok()?200:400,result);return;
        }
        if(path.startsWith("/api/")){
            Session s=session(x);State current=state();User u=authenticated(current,s);
            if(path.equals("/api/session")&&method.equals("GET")){json(x,200,Map.of("user",Views.user(u),"csrf",s.csrf));return;}
            if(path.equals("/api/app")&&method.equals("GET")){json(x,200,Views.app(current,u));return;}
            if(path.equals("/api/logout")&&method.equals("POST")){csrf(x,s);sessions.remove(s.token);x.getResponseHeaders().add("Set-Cookie","bs_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");json(x,200,Map.of("ok",true));return;}
            if(path.equals("/api/command")&&method.equals("POST")){
                csrf(x,s);Map<String,String> f=body(x);String action=f.remove("action"),request=f.remove("id");int b=branch(f);f.remove("branch");
                require(action!=null&&!action.equals("REGISTER"),400,"Invalid action");
                f.remove("actor");f.remove("passwordHash");
                if(action.equals("PASSWORD_CHANGE")){String old=f.remove("currentPassword"),next=f.remove("newPassword");require(old!=null&&old.length()<=128&&Passwords.verify(old,u.password()),403,"Current password is incorrect");Passwords.validate(next);f.put("passwordHash",Passwords.hash(next));}
                Result result=submit(new Command(request,u.id(),action,f),b);json(x,result.ok()?200:400,result);return;
            }
            if(path.equals("/api/cluster")&&method.equals("GET")){
                require(u.role().equals("ADMIN"),403,"Administrator authorization required");List<Object> out=new ArrayList<>();List<NodeStatus> live=statuses();
                for(int n=1;n<=3;n++){final int id=n;NodeStatus ns=live.stream().filter(a->a.id()==id).findFirst().orElse(null);if(ns==null)out.add(Map.of("id",n,"offline",true,"managed",nodes.running(n)));else out.add(Map.of("id",n,"offline",false,"ready",ns.ready(),"leader",ns.leader(),"clock",ns.clock(),"sequence",ns.state().sequence,"digest",fingerprint(ns.state()),"events",ns.events(),"accounts",ns.state().accounts.size()));}
                json(x,200,Map.of("nodes",out,"model","Controlled localhost crash/restart; Bully-style election, not consensus"));return;
            }
            if(path.equals("/api/cluster/control")&&method.equals("POST")){
                csrf(x,s);require(u.role().equals("ADMIN"),403,"Administrator authorization required");Map<String,String> f=body(x);int n=branch(f);String op=f.get("operation");
                require(Set.of("START","STOP").contains(op==null?"":op),400,"Invalid node operation");
                if(op.equals("STOP")){long count=java.util.stream.IntStream.rangeClosed(1,3).filter(nodes::running).count();require(count>1,400,"Keep at least one node running so the console can recover the cluster");nodes.stop(n);}else nodes.start(n);
                json(x,200,Map.of("ok",true,"message","Node "+n+" "+(op.equals("STOP")?"stopped":"started")+". Wait for the cluster to settle."));return;
            }
            if(path.equals("/api/statement")&&method.equals("GET")){
                Map<String,String> q=form(x.getRequestURI().getRawQuery());Account a=current.accounts.get(q.get("account"));require(a!=null&&(a.owner().equals(u.id())||!u.role().equals("CUSTOMER")),403,"You cannot export this account");
                String from=q.getOrDefault("from","0000-01-01"),to=q.getOrDefault("to","9999-12-31");try{LocalDate.parse(from);LocalDate.parse(to);}catch(Exception e){throw new HttpFailure(400,"Invalid statement dates");}require(from.compareTo(to)<=0,400,"Start date must not follow end date");
                StringBuilder csv=new StringBuilder("Reference,Date,Type,Description,Debit INR,Credit INR,Account\r\n");
                for(Transaction t:current.transactions){String date=t.time().substring(0,10);if(date.compareTo(from)<0||date.compareTo(to)>0)continue;if(!t.from().equals(a.id())&&!t.to().equals(a.id()))continue;
                    csv.append(cell(t.id())).append(',').append(cell(t.time())).append(',').append(cell(t.type())).append(',').append(cell(t.description())).append(',').append(t.from().equals(a.id())?rupees(t.amount()):"").append(',').append(t.to().equals(a.id())?rupees(t.amount()):"").append(',').append(cell(a.id())).append("\r\n");}
                x.getResponseHeaders().set("Content-Disposition","attachment; filename=BranchSync-statement-"+a.id()+".csv");bytes(x,200,"text/csv; charset=utf-8",csv.toString().getBytes(StandardCharsets.UTF_8));return;
            }
            throw new HttpFailure(404,"Endpoint not found");
        }
        require(method.equals("GET"),405,"GET required");Path base=Path.of("web").toAbsolutePath().normalize();String name=path.equals("/")?"index.html":path.substring(1);Path file=base.resolve(name).normalize();
        require(file.startsWith(base)&&Files.isRegularFile(file),404,"Page not found");String mime=name.endsWith(".css")?"text/css":name.endsWith(".js")?"text/javascript":name.endsWith(".svg")?"image/svg+xml":name.endsWith(".html")?"text/html":"application/octet-stream";bytes(x,200,mime+"; charset=utf-8",Files.readAllBytes(file));
    }
    private static String rupees(long n){return BigDecimal.valueOf(n,2).toPlainString();}
    private static String cell(String text){if(text.matches("^[=+@\\-].*"))text="'"+text;return "\""+text.replace("\"","\"\"")+"\"";}
    private void handle(HttpExchange x)throws IOException{
        try{route(x);}catch(HttpFailure e){json(x,e.code,Map.of("error",e.getMessage()));}
        catch(IllegalArgumentException e){json(x,400,Map.of("error",e.getMessage()==null?"Invalid request":e.getMessage()));}
        catch(Exception e){String msg=e.getMessage()==null?"Banking service unavailable":e.getMessage();if(msg.length()>400)msg=msg.substring(0,400);json(x,503,Map.of("error",msg,"hint","If you submitted a transaction, retain its request ID and check history before retrying."));}
    }
    public static void main(String[] args)throws Exception{
        System.setProperty("sun.rmi.transport.tcp.responseTimeout","2500");System.setProperty("sun.rmi.transport.tcp.handshakeTimeout","1500");System.setProperty("sun.rmi.transport.connectionTimeout","1500");
        Setup.initialize();ApiServer app=new ApiServer();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",Config.PORT),0);
        try{for(int n=1;n<=3;n++)app.nodes.start(n);}catch(Exception e){app.nodes.close();server.stop(0);throw e;}
        server.setExecutor(Executors.newFixedThreadPool(20));server.createContext("/",app::handle);
        Runtime.getRuntime().addShutdownHook(new Thread(()->{app.nodes.close();server.stop(0);}));server.start();
        System.out.println("BranchSync Bank is starting: http://localhost:"+Config.PORT+" | All node logs: "+Config.DATA.resolve("logs").toAbsolutePath());
        System.out.println("Wait for the three nodes to elect a leader. Ctrl+C stops this gateway and its node processes.");
    }
}
