package branchsync.web;
import branchsync.domain.Models.*;
import java.util.*;
import java.util.stream.*;

/** Explicit response projections: passwords, internal commands and cluster secrets never leave this layer. */
public final class Views {
    private Views(){}
    public static Map<String,Object> user(User u){return Map.of("id",u.id(),"username",u.username(),"name",u.name(),"email",u.email(),"phone",u.phone(),"role",u.role(),"status",u.status(),"kyc",u.kyc());}
    public static boolean visible(State s,User u,Transaction t){if(!u.role().equals("CUSTOMER"))return true;return owns(s,u,t.from())||owns(s,u,t.to());}
    private static boolean owns(State s,User u,String id){Account a=s.accounts.get(id);if(a!=null)return a.owner().equals(u.id());Deposit d=s.deposits.get(id);if(d!=null)return d.owner().equals(u.id());Loan l=s.loans.get(id);return l!=null&&l.owner().equals(u.id());}
    public static Map<String,Object> app(State s,User u){
        boolean staff=!u.role().equals("CUSTOMER");Map<String,Object> out=new LinkedHashMap<>();
        out.put("user",user(u));out.put("sequence",s.sequence);
        out.put("accounts",s.accounts.values().stream().filter(a->staff||a.owner().equals(u.id())).toList());
        out.put("beneficiaries",s.beneficiaries.values().stream().filter(b->b.owner().equals(u.id())).map(b->{User owner=s.users.get(s.accounts.get(b.account()).owner());return Map.of("id",b.id(),"account",b.account(),"nickname",b.nickname(),"name",owner.name());}).toList());
        out.put("deposits",s.deposits.values().stream().filter(d->staff||d.owner().equals(u.id())).toList());
        out.put("loans",s.loans.values().stream().filter(d->staff||d.owner().equals(u.id())).toList());
        out.put("requests",s.requests.values().stream().filter(r->staff||r.owner().equals(u.id())).toList());
        out.put("transactions",s.transactions.stream().filter(t->visible(s,u,t)).toList());
        out.put("users",staff?s.users.values().stream().map(Views::user).toList():List.of());
        out.put("audit",staff?s.audit.stream().skip(Math.max(0,s.audit.size()-250)).toList():List.of());
        return out;
    }
}
