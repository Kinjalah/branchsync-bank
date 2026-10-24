package branchsync.application;
import branchsync.domain.Models.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.*;
import java.util.*;

/** Business rules and authorization. No HTTP, RMI or disk dependencies. */
public final class BankingService {
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    private static String field(Command c,String key){return c.fields().getOrDefault(key,"").trim();}
    private static String text(Command c,String key,int min,int max){String x=field(c,key);require(x.length()>=min&&x.length()<=max,"Invalid "+key+" ("+min+"–"+max+" characters)");return x;}
    public static long money(String value){try{long n=new BigDecimal(value).movePointRight(2).longValueExact();require(n>0&&n<=100_000_000L,"Amount must be between ₹0.01 and ₹10,00,000");return n;}catch(ArithmeticException|NumberFormatException e){throw new IllegalArgumentException("Enter an amount with at most two decimal places");}}
    private static User actor(State s,Command c){User u=s.users.get(c.actor());require(u!=null&&u.status().equals("ACTIVE"),"Access denied: active user required");return u;}
    private static void staff(User u){require(Set.of("TELLER","ADMIN").contains(u.role()),"Staff authorization required");}
    private static void customer(User u){require(u.role().equals("CUSTOMER"),"Customer authorization required");require(u.kyc().equals("VERIFIED"),"Your KYC verification is pending");}
    private static Account account(State s,String id){Account a=s.accounts.get(id);require(a!=null,"Account not found");require(a.status().equals("ACTIVE"),"Account is frozen");User owner=s.users.get(a.owner());require(owner!=null&&owner.status().equals("ACTIVE"),"Account owner is suspended");return a;}
    private static Account own(State s,User u,String id){Account a=account(s,id);require(a.owner().equals(u.id()),"You are not authorized to use this account");return a;}
    private static void balance(State s,String id,long delta){Account a=s.accounts.get(id);long n=Math.addExact(a.balance(),delta);require(n>=0,"Insufficient available balance");require(n<=1_000_000_000_000L,"Account balance limit exceeded");s.accounts.put(id,new Account(a.id(),a.owner(),a.label(),n,a.status(),a.cardStatus(),a.cardLast4(),a.dailyLimit()));}
    private static String id(State s,String prefix){return prefix+String.format("%06d",s.sequence);}
    private static void debit(State s,Account a,long n){
        long today=s.transactions.stream().filter(t->t.from().equals(a.id())&&t.time().substring(0,10).equals(LocalDate.now(ZoneOffset.UTC).toString())).mapToLong(Transaction::amount).sum();
        require(today+n<=a.dailyLimit(),"Daily debit limit exceeded");balance(s,a.id(),-n);
    }
    private static void transaction(State s,Command c,String type,String from,String to,long amount,String description,long clock,int leader){
        s.transactions.add(new Transaction(id(s,"BS"),s.sequence,c.id(),type,from,to,amount,description,c.actor(),clock,leader,Instant.now().toString()));
    }
    public static Result apply(State s,Command c,long clock,int leader){
        require(c!=null&&c.id()!=null&&c.id().matches("[A-Za-z0-9-]{8,80}"),"Valid request ID required");
        require(c.action()!=null,"Action required");
        User u=null;String target="",message;Map<String,String> data=new LinkedHashMap<>();
        if(!c.action().equals("REGISTER"))u=actor(s,c);
        switch(c.action()){
            case "REGISTER"->{
                String username=text(c,"username",3,40).toLowerCase(Locale.ROOT);require(username.matches("[a-z][a-z0-9._]{2,39}"),"Username must start with a letter and use letters, digits, dots or underscores");
                require(s.users.values().stream().noneMatch(x->x.username().equals(username)),"Username already registered");
                String name=text(c,"name",2,80),email=text(c,"email",5,120),phone=text(c,"phone",10,15),hash=text(c,"passwordHash",60,300);
                require(email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),"Valid email required");require(phone.matches("[0-9+]{10,15}"),"Valid phone required");
                require(hash.startsWith("600000:"),"Invalid password encoding");
                target=id(s,"U");String acc=id(s,"20");
                s.users.put(target,new User(target,username,name,email,phone,"CUSTOMER",hash,"ACTIVE","PENDING",1));
                s.accounts.put(acc,new Account(acc,target,"Everyday Savings",0,"ACTIVE","ACTIVE",String.format("%04d",s.sequence%10000),500_000_00L));
                data.put("account",acc);message="Account created. Sign in to track your KYC verification.";
            }
            case "BENEFICIARY_ADD"->{customer(u);Account to=account(s,text(c,"account",3,30));require(!to.owner().equals(u.id()),"Use your own account directly");final String owner=u.id();require(s.beneficiaries.values().stream().noneMatch(b->b.owner().equals(owner)&&b.account().equals(to.id())),"Beneficiary already saved");target=id(s,"B");s.beneficiaries.put(target,new Beneficiary(target,u.id(),to.id(),text(c,"nickname",2,40)));message="Beneficiary added";}
            case "BENEFICIARY_DELETE"->{customer(u);target=field(c,"beneficiary");Beneficiary b=s.beneficiaries.get(target);require(b!=null&&b.owner().equals(u.id()),"Beneficiary not found");s.beneficiaries.remove(target);message="Beneficiary removed";}
            case "TRANSFER"->{customer(u);Account from=own(s,u,field(c,"account")),to=account(s,field(c,"destination"));require(!from.id().equals(to.id()),"Choose a different destination");final String owner=u.id();require(to.owner().equals(owner)||s.beneficiaries.values().stream().anyMatch(b->b.owner().equals(owner)&&b.account().equals(to.id())),"Save the destination as a beneficiary first");long n=money(field(c,"amount"));debit(s,from,n);balance(s,to.id(),n);target=from.id();transaction(s,c,"TRANSFER",from.id(),to.id(),n,text(c,"description",0,120),clock,leader);message="Transfer completed";data.put("reference",id(s,"BS"));}
            case "BILL_PAY"->{customer(u);Account a=own(s,u,field(c,"account"));String biller=field(c,"biller");require(Set.of("ELECTRICITY","BROADBAND","MOBILE","WATER").contains(biller),"Select a supported biller");String consumer=text(c,"consumer",4,40);long n=money(field(c,"amount"));debit(s,a,n);target=a.id();transaction(s,c,"BILL_PAYMENT",a.id(),"BILLER:"+biller,n,biller+" / "+consumer,clock,leader);message="Demo bill payment recorded";data.put("reference",id(s,"BS"));}
            case "CARD_TOGGLE"->{customer(u);Account a=own(s,u,field(c,"account"));String status=a.cardStatus().equals("ACTIVE")?"FROZEN":"ACTIVE";s.accounts.put(a.id(),new Account(a.id(),a.owner(),a.label(),a.balance(),a.status(),status,a.cardLast4(),a.dailyLimit()));target=a.id();message="Card "+status.toLowerCase(Locale.ROOT);}
            case "LIMIT_UPDATE"->{customer(u);Account a=own(s,u,field(c,"account"));long n=money(field(c,"amount"));require(n>=10000&&n<=500_000_00L,"Daily limit must be ₹100–₹5,00,000");s.accounts.put(a.id(),new Account(a.id(),a.owner(),a.label(),a.balance(),a.status(),a.cardStatus(),a.cardLast4(),n));target=a.id();message="Daily debit limit updated";}
            case "FD_OPEN"->{customer(u);Account a=own(s,u,field(c,"account"));long n=money(field(c,"amount"));require(n>=100000,"Minimum fixed deposit is ₹1,000");int months=Integer.parseInt(field(c,"months"));require(Set.of(6,12,24).contains(months),"Select a 6, 12 or 24 month term");debit(s,a,n);target=id(s,"FD");LocalDate now=LocalDate.now(ZoneOffset.UTC);s.deposits.put(target,new Deposit(target,u.id(),a.id(),n,months,now.toString(),now.plusMonths(months).toString(),"ACTIVE"));transaction(s,c,"FD_OPEN",a.id(),target,n,"Fixed deposit opened",clock,leader);message="Fixed deposit opened";}
            case "FD_CLOSE"->{customer(u);Deposit d=s.deposits.get(field(c,"deposit"));require(d!=null&&d.owner().equals(u.id())&&d.status().equals("ACTIVE"),"Active deposit not found");Account a=own(s,u,d.account());long interest=0;LocalDate now=LocalDate.now(ZoneOffset.UTC);if(!now.isBefore(LocalDate.parse(d.maturity()))){long days=ChronoUnit.DAYS.between(LocalDate.parse(d.opened()),LocalDate.parse(d.maturity()));interest=BigDecimal.valueOf(d.principal()).multiply(new BigDecimal("0.06")).multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(365),0,RoundingMode.DOWN).longValueExact();}balance(s,a.id(),d.principal()+interest);target=d.id();s.deposits.put(target,new Deposit(d.id(),d.owner(),d.account(),d.principal(),d.months(),d.opened(),d.maturity(),"CLOSED"));transaction(s,c,"FD_CLOSE",target,a.id(),d.principal()+interest,interest>0?"Matured deposit including interest":"Early closure: principal returned",clock,leader);message=interest>0?"Matured deposit redeemed":"Deposit closed; principal returned without interest";}
            case "REQUEST_CREATE"->{customer(u);Account a=own(s,u,field(c,"account"));String type=field(c,"type");require(Set.of("CASH_DEPOSIT","CASH_WITHDRAWAL","LOAN","CHEQUE_BOOK","CARD_REPLACEMENT","SUPPORT").contains(type),"Invalid service type");long n=Set.of("CASH_DEPOSIT","CASH_WITHDRAWAL","LOAN").contains(type)?money(field(c,"amount")):0;if(type.equals("LOAN"))require(n>=100_000&&n<=50_000_000,"Loan principal must be ₹1,000–₹5,00,000");target=id(s,"SR");s.requests.put(target,new ServiceRequest(target,u.id(),type,a.id(),n,text(c,"details",5,240),"PENDING",Instant.now().toString(),"",""));message="Request submitted to the branch team";data.put("request",target);}
            case "REQUEST_CANCEL"->{customer(u);ServiceRequest r=s.requests.get(field(c,"request"));require(r!=null&&r.owner().equals(u.id())&&r.status().equals("PENDING"),"Pending request not found");target=r.id();s.requests.put(target,new ServiceRequest(r.id(),r.owner(),r.type(),r.account(),r.amount(),r.details(),"CANCELLED",r.created(),u.id(),"Cancelled by customer"));message="Request cancelled";}
            case "REQUEST_DECIDE"->{staff(u);ServiceRequest r=s.requests.get(field(c,"request"));require(r!=null&&r.status().equals("PENDING"),"Request has already been decided or does not exist");String decision=field(c,"decision");require(Set.of("APPROVE","REJECT").contains(decision),"Invalid decision");String note=text(c,"note",5,200);String status="REJECTED";
                if(decision.equals("APPROVE")){Account a=account(s,r.account());require(s.users.get(r.owner()).kyc().equals("VERIFIED"),"Customer KYC is not verified");status="COMPLETED";
                    switch(r.type()){
                        case "CASH_DEPOSIT"->{balance(s,a.id(),r.amount());transaction(s,c,"CASH_DEPOSIT","CASH_DESK",a.id(),r.amount(),"Approved cash deposit / "+r.id(),clock,leader);}
                        case "CASH_WITHDRAWAL"->{debit(s,a,r.amount());transaction(s,c,"CASH_WITHDRAWAL",a.id(),"CASH_DESK",r.amount(),"Approved cash withdrawal / "+r.id(),clock,leader);}
                        case "LOAN"->{balance(s,a.id(),r.amount());String loan=id(s,"LN");s.loans.put(loan,new Loan(loan,r.owner(),a.id(),r.amount(),r.amount(),12,"ACTIVE",Instant.now().toString()));transaction(s,c,"LOAN_DISBURSAL","LENDING_DESK",a.id(),r.amount(),"Demo principal-only loan / "+loan,clock,leader);}
                        case "CARD_REPLACEMENT"->{String last=String.format("%04d",(s.sequence+7313)%10000);s.accounts.put(a.id(),new Account(a.id(),a.owner(),a.label(),a.balance(),a.status(),"ACTIVE",last,a.dailyLimit()));}
                        default->{/* Cheque book and support completion are recorded service outcomes. */}
                    }
                }
                target=r.id();s.requests.put(target,new ServiceRequest(r.id(),r.owner(),r.type(),r.account(),r.amount(),r.details(),status,r.created(),u.id(),note));message="Request "+status.toLowerCase(Locale.ROOT);}
            case "LOAN_REPAY"->{customer(u);Loan l=s.loans.get(field(c,"loan"));require(l!=null&&l.owner().equals(u.id())&&l.status().equals("ACTIVE"),"Active loan not found");Account a=own(s,u,field(c,"account"));long n=money(field(c,"amount"));require(n<=l.outstanding(),"Amount exceeds outstanding principal");debit(s,a,n);target=l.id();s.loans.put(target,new Loan(l.id(),l.owner(),l.account(),l.principal(),l.outstanding()-n,l.months(),l.outstanding()==n?"CLOSED":"ACTIVE",l.opened()));transaction(s,c,"LOAN_REPAYMENT",a.id(),l.id(),n,"Principal repayment",clock,leader);message="Loan repayment completed";}
            case "KYC_DECIDE"->{staff(u);User who=s.users.get(field(c,"user"));require(who!=null&&who.role().equals("CUSTOMER"),"Customer not found");String kyc=field(c,"kyc");require(Set.of("VERIFIED","REJECTED").contains(kyc),"Invalid KYC decision");target=who.id();s.users.put(target,new User(who.id(),who.username(),who.name(),who.email(),who.phone(),who.role(),who.password(),who.status(),kyc,who.version()));message="KYC status updated";}
            case "ACCOUNT_STATUS"->{staff(u);Account a=s.accounts.get(field(c,"account"));require(a!=null,"Account not found");String status=field(c,"status");require(Set.of("ACTIVE","FROZEN").contains(status),"Invalid account status");target=a.id();s.accounts.put(target,new Account(a.id(),a.owner(),a.label(),a.balance(),status,a.cardStatus(),a.cardLast4(),a.dailyLimit()));message="Account "+status.toLowerCase(Locale.ROOT);}
            case "USER_STATUS"->{require(u.role().equals("ADMIN"),"Administrator authorization required");User who=s.users.get(field(c,"user"));require(who!=null&&!who.id().equals(u.id()),"Cannot change your own access");require(who.role().equals("CUSTOMER"),"Only customer access can be changed in this console");String status=field(c,"status");require(Set.of("ACTIVE","SUSPENDED").contains(status),"Invalid user status");target=who.id();s.users.put(target,new User(who.id(),who.username(),who.name(),who.email(),who.phone(),who.role(),who.password(),status,who.kyc(),who.version()+1));message="Customer access updated";}
            case "PROFILE_UPDATE"->{target=u.id();String name=text(c,"name",2,80),email=text(c,"email",5,120),phone=text(c,"phone",10,15);require(email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"),"Valid email required");require(phone.matches("[0-9+]{10,15}"),"Valid phone required");s.users.put(target,new User(u.id(),u.username(),name,email,phone,u.role(),u.password(),u.status(),u.kyc(),u.version()));message="Profile updated";}
            case "PASSWORD_CHANGE"->{String hash=text(c,"passwordHash",60,300);require(hash.startsWith("600000:"),"Invalid password encoding");target=u.id();s.users.put(target,new User(u.id(),u.username(),u.name(),u.email(),u.phone(),u.role(),hash,u.status(),u.kyc(),u.version()+1));message="Password changed. Sign in again on all devices.";}
            default->throw new IllegalArgumentException("Unsupported banking action");
        }
        s.audit.add(new Audit(s.sequence,u==null?"NEW_CUSTOMER":u.id(),c.action(),target,message,Instant.now().toString()));
        return new Result(true,message,s.sequence,data);
    }
}
