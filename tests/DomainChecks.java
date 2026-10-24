package branchsync.tests;
import branchsync.application.BankingService;
import branchsync.domain.Models.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Deterministic tests for paths that cannot be reached by waiting in a short live demo. */
public final class DomainChecks {
    private static int passed;
    private static void check(String name,boolean ok){if(!ok)throw new AssertionError(name);passed++;System.out.println("PASS: "+name);}
    private static State state(){State s=new State();s.sequence=1;s.users.put("U1",new User("U1","user","Test User","test@example.test","9876500000","CUSTOMER","test-only","ACTIVE","VERIFIED",1));s.accounts.put("A1",new Account("A1","U1","Savings",0,"ACTIVE","ACTIVE","1234",50_000_000));return s;}
    private static Command cmd(String action,Map<String,String> fields){return new Command(UUID.randomUUID().toString(),"U1",action,fields);}
    public static void main(String[] args){
        State mature=state();LocalDate end=LocalDate.now(ZoneOffset.UTC),start=end.minusYears(1);long principal=100_000;
        mature.deposits.put("FD1",new Deposit("FD1","U1","A1",principal,12,start.toString(),end.toString(),"ACTIVE"));
        Result r=BankingService.apply(mature,cmd("FD_CLOSE",Map.of("deposit","FD1")),1,3);
        long interest=principal*6*ChronoUnit.DAYS.between(start,end)/36500;
        check("Matured deposit returns principal plus original-term simple interest",r.ok()&&mature.accounts.get("A1").balance()==principal+interest);
        check("Matured deposit records closure and a receipt transaction",mature.deposits.get("FD1").status().equals("CLOSED")&&mature.transactions.size()==1);
        State early=state();early.deposits.put("FD2",new Deposit("FD2","U1","A1",principal,6,end.toString(),end.plusMonths(6).toString(),"ACTIVE"));
        BankingService.apply(early,cmd("FD_CLOSE",Map.of("deposit","FD2")),1,3);
        check("Early deposit redemption has no unearned interest",early.accounts.get("A1").balance()==principal);
        State original=state(),copy=original.copy();try{BankingService.apply(copy,cmd("USER_STATUS",Map.of("user","U1","status","SUSPENDED")),1,3);throw new AssertionError("Unauthorized action accepted");}catch(IllegalArgumentException expected){check("Domain layer independently enforces administrator authorization",original.users.get("U1").status().equals("ACTIVE"));}
        try{BankingService.money("1.001");throw new AssertionError("Fractional paise accepted");}catch(IllegalArgumentException expected){check("Domain amount parser rejects fractional paise",true);}
        try{BankingService.money("-1.00");throw new AssertionError("Negative amount accepted");}catch(IllegalArgumentException expected){check("Domain amount parser rejects negative amounts",true);}
        System.out.println("\n"+passed+" domain checks passed.");
    }
}
