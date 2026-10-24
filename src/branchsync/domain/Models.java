package branchsync.domain;
import java.io.Serializable;
import java.util.*;

/** Domain data shared over RMI. Money is always stored as integer paise. */
public final class Models {
    private Models() {}
    public record User(String id,String username,String name,String email,String phone,String role,
                       String password,String status,String kyc,int version) implements Serializable {}
    public record Account(String id,String owner,String label,long balance,String status,String cardStatus,
                          String cardLast4,long dailyLimit) implements Serializable {}
    public record Beneficiary(String id,String owner,String account,String nickname) implements Serializable {}
    public record Deposit(String id,String owner,String account,long principal,int months,String opened,
                          String maturity,String status) implements Serializable {}
    public record Loan(String id,String owner,String account,long principal,long outstanding,int months,String status,String opened) implements Serializable {}
    public record ServiceRequest(String id,String owner,String type,String account,long amount,String details,
                                 String status,String created,String decidedBy,String decision) implements Serializable {}
    public record Transaction(String id,long sequence,String requestId,String type,String from,String to,
                              long amount,String description,String actor,long clock,int leader,String time) implements Serializable {}
    public record Audit(long sequence,String actor,String action,String target,String detail,String time) implements Serializable {}
    public record Command(String id,String actor,String action,Map<String,String> fields) implements Serializable {
        public Command { fields=Collections.unmodifiableMap(new TreeMap<>(fields)); }
    }
    public record Result(boolean ok,String message,long sequence,Map<String,String> data) implements Serializable {}
    public record Event(long clock,String message) implements Serializable {}
    public record NodeStatus(int id,boolean ready,int leader,long clock,State state,List<Event> events) implements Serializable {}
    public static final class State implements Serializable {
        private static final long serialVersionUID=2L;
        public long sequence;
        public LinkedHashMap<String,User> users=new LinkedHashMap<>();
        public LinkedHashMap<String,Account> accounts=new LinkedHashMap<>();
        public LinkedHashMap<String,Beneficiary> beneficiaries=new LinkedHashMap<>();
        public LinkedHashMap<String,Deposit> deposits=new LinkedHashMap<>();
        public LinkedHashMap<String,Loan> loans=new LinkedHashMap<>();
        public LinkedHashMap<String,ServiceRequest> requests=new LinkedHashMap<>();
        public ArrayList<Transaction> transactions=new ArrayList<>();
        public ArrayList<Audit> audit=new ArrayList<>();
        public LinkedHashMap<String,Command> commands=new LinkedHashMap<>();
        public LinkedHashMap<String,Result> results=new LinkedHashMap<>();
        public State copy() {
            State s=new State(); s.sequence=sequence; s.users.putAll(users); s.accounts.putAll(accounts);
            s.beneficiaries.putAll(beneficiaries); s.deposits.putAll(deposits); s.loans.putAll(loans); s.requests.putAll(requests);
            s.transactions.addAll(transactions); s.audit.addAll(audit); s.commands.putAll(commands); s.results.putAll(results); return s;
        }
    }
}
