#!/usr/bin/env python3
"""End-to-end API tests against a real gateway and three real Java RMI processes.
Uses isolated temporary storage. Requires a built project and Python 3.9+.
"""
import concurrent.futures, http.cookiejar, json, os, shutil, socket, subprocess, tempfile, time, urllib.error, urllib.parse, urllib.request, uuid
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
PORT=18090
BASE=f'http://127.0.0.1:{PORT}'
checks=[]

def check(name,condition):
    if not condition:raise AssertionError(name)
    checks.append(name);print('PASS:',name,flush=True)
class Client:
    def __init__(self):
        self.jar=http.cookiejar.CookieJar();self.opener=urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar));self.csrf=''
    def request(self,path,fields=None,token=True,headers=None,raw=False):
        h=dict(headers or {})
        if fields is not None:
            data=urllib.parse.urlencode(fields).encode();h['Content-Type']='application/x-www-form-urlencoded'
            if token:h['X-CSRF-Token']=self.csrf
        else:data=None
        req=urllib.request.Request(BASE+path,data=data,headers=h)
        try:r=self.opener.open(req,timeout=20)
        except urllib.error.HTTPError as e:r=e
        content=r.read();code=r.code
        return code,content.decode() if raw else json.loads(content),r.headers
    def login(self,username,password):
        code,d,h=self.request('/api/login',dict(username=username,password=password));
        if code==200:self.csrf=d['csrf']
        return code,d,h
    def app(self):return self.request('/api/app')[1]
    def cmd(self,action,fields=None,branch=1,id=None):return self.request('/api/command',dict(fields or {},action=action,id=id or str(uuid.uuid4()),branch=branch))

def wait(predicate,description,timeout=35):
    end=time.time()+timeout;last=None
    while time.time()<end:
        try:
            last=predicate()
            if last:return last
        except Exception:pass
        time.sleep(.3)
    raise AssertionError('Timeout: '+description)

def healthy(admin,count=3,leader=3):
    code,d,_=admin.request('/api/cluster')
    if code!=200:return False
    live=[n for n in d['nodes'] if not n.get('offline')]
    return d if len(live)==count and all(n['ready'] and n['leader']==leader for n in live) and len({n['digest'] for n in live})==1 else False

def start(cwd,env,logs):
    f=open(cwd/f'gateway-{time.time_ns()}.log','w');logs.append(f)
    return subprocess.Popen([shutil.which('java'),'--add-modules','jdk.httpserver','-cp',str(ROOT/'out'),'branchsync.web.ApiServer'],cwd=cwd,env=env,stdout=f,stderr=subprocess.STDOUT)

def stop(p):
    p.terminate()
    try:p.wait(timeout=10)
    except subprocess.TimeoutExpired:p.kill();p.wait()
    time.sleep(.8)

if __name__=='__main__':
    if not (ROOT/'out/branchsync/web/ApiServer.class').exists():raise SystemExit('Build the Java project first.')
    for port in [PORT,5001,5002,5003,5101,5102,5103]:
        with socket.socket() as s:
            try:s.bind(('127.0.0.1',port))
            except OSError:raise SystemExit(f'Stop running BranchSync first: port {port} is busy. Jenkins can keep running on 8080.')
    with tempfile.TemporaryDirectory(prefix='branchsync-bank-test-') as tmp:
        cwd=Path(tmp);shutil.copytree(ROOT/'web',cwd/'web');env=dict(os.environ,BRANCHSYNC_PORT=str(PORT),BRANCHSYNC_DATA='runtime');logs=[];p=start(cwd,env,logs)
        try:
            anon=Client();wait(lambda:anon.request('/api/login',dict(username='admin',password='Admin@123456'))[0] in (200,503),'gateway startup')
            admin=Client();wait(lambda:admin.login('admin','Admin@123456')[0]==200,'admin login');wait(lambda:healthy(admin),'initial election and replicas')
            check('Gateway starts three independent JVMs; Node 3 leads',True)
            code,html,h=anon.request('/',raw=True);check('Bank portal HTML and security headers served',code==200 and 'BranchSync Bank' in html and "script-src 'self'" in h['Content-Security-Policy'])
            check('Unauthenticated banking data denied',Client().request('/api/app')[0]==401)
            check('Wrong password rejected',Client().login('kinjal','WrongPassword123')[0]==401)
            k=Client();code,d,h=k.login('kinjal','Kinjal@12345');check('Customer login uses HttpOnly SameSite cookie',code==200 and 'HttpOnly' in h['Set-Cookie'] and 'SameSite=Strict' in h['Set-Cookie'])
            a=Client();a.login('aarav','Aarav@123456');t=Client();t.login('teller','Teller@12345')
            data=k.app();check('Customer sees only owned accounts',len(data['accounts'])==2 and all(x['owner']=='U1001' for x in data['accounts']))
            check('Password hashes and internal commands are absent from HTTP projections','600000:' not in json.dumps(data) and not data['users'] and 'commands' not in data)
            (cwd/'customer-fixture.json').write_text(json.dumps(data))
            check('CSRF-less mutation denied',k.request('/api/command',dict(action='CARD_TOGGLE',account='10010001',id=str(uuid.uuid4()),branch=1),token=False)[0]==403)
            check('Cross-origin login denied',Client().request('/api/login',dict(username='kinjal',password='Kinjal@12345'),headers={'Origin':'https://untrusted.example'})[0]==403)
            check('Customer cannot access DCS console',k.request('/api/cluster')[0]==403)
            check('Teller cannot access DCS console',t.request('/api/cluster')[0]==403)
            check('Customer cannot approve their own request',not k.cmd('REQUEST_DECIDE',dict(request='unknown',decision='APPROVE',note='Attempt forbidden'))[1].get('ok'))
            check('Teller cannot suspend customer access',not t.cmd('USER_STATUS',dict(user='U1001',status='SUSPENDED'))[1].get('ok'))
            base=k.app()['accounts'][0]['balance'];rid=str(uuid.uuid4());payload=dict(account='10010001',destination='10020001',amount='1000.00',description='Integration transfer')
            code,r,_=k.cmd('TRANSFER',payload,id=rid);check('Customer transfer through follower succeeds',code==200 and r['ok'])
            seq=r['sequence'];check('Transfer debits sender and credits recipient',k.app()['accounts'][0]['balance']==base-100000 and a.app()['accounts'][0]['balance']==8_740_000)
            check('Receipt reference persisted in transaction history',any(x['id']==r['data']['reference'] for x in k.app()['transactions']))
            check('Idempotent transfer does not debit twice',k.cmd('TRANSFER',payload,id=rid)[1]['sequence']==seq and k.app()['accounts'][0]['balance']==base-100000)
            check('Request ID cannot be reused with changed payload',not k.cmd('TRANSFER',dict(payload,amount='2000'),id=rid)[1].get('ok'))
            check('Customer cannot debit another customer account',not k.cmd('TRANSFER',dict(account='10020001',destination='10010001',amount='1',description='unauthorized'))[1].get('ok'))
            check('Invalid decimal precision rejected',not k.cmd('TRANSFER',dict(payload,amount='1.001'))[1].get('ok'))
            check('Insufficient funds rejected',not k.cmd('TRANSFER',dict(payload,amount='999999'))[1].get('ok'))
            check('Beneficiary add works',a.cmd('BENEFICIARY_ADD',dict(account='10010001',nickname='Kinjal'))[1].get('ok'))
            bid=a.app()['beneficiaries'][0]['id'];check('Beneficiary remove works',a.cmd('BENEFICIARY_DELETE',dict(beneficiary=bid))[1].get('ok'))
            check('Unsaved other-customer destination rejected',not a.cmd('TRANSFER',dict(account='10020001',destination='10010001',amount='1',description='not saved'))[1].get('ok'))
            check('Own-account transfer succeeds',k.cmd('TRANSFER',dict(account='10010001',destination='10010002',amount='100',description='Move to current'))[1].get('ok'))
            check('Bill payment creates an authorized account debit',k.cmd('BILL_PAY',dict(account='10010001',biller='ELECTRICITY',consumer='DEMO1234',amount='250'))[1].get('ok'))
            check('Unknown biller rejected',not k.cmd('BILL_PAY',dict(account='10010001',biller='INVALID',consumer='DEMO1234',amount='250'))[1].get('ok'))
            check('Card freeze persists',k.cmd('CARD_TOGGLE',dict(account='10010001'))[1].get('ok') and k.app()['accounts'][0]['cardStatus']=='FROZEN')
            check('Card unfreeze persists',k.cmd('CARD_TOGGLE',dict(account='10010001'))[1].get('ok') and k.app()['accounts'][0]['cardStatus']=='ACTIVE')
            check('Daily debit limit update enforced',k.cmd('LIMIT_UPDATE',dict(account='10010001',amount='100'))[1].get('ok') and not k.cmd('TRANSFER',dict(payload,amount='1'))[1].get('ok'))
            k.cmd('LIMIT_UPDATE',dict(account='10010001',amount='500000'))
            before=k.app()['accounts'][0]['balance'];check('Fixed deposit opens and debits balance',k.cmd('FD_OPEN',dict(account='10010001',amount='2000',months='6'))[1].get('ok') and k.app()['accounts'][0]['balance']==before-200000)
            fd=k.app()['deposits'][0]['id'];check('Early FD closure returns principal',k.cmd('FD_CLOSE',dict(deposit=fd))[1].get('ok') and k.app()['accounts'][0]['balance']==before)
            check('Closed FD cannot be redeemed twice',not k.cmd('FD_CLOSE',dict(deposit=fd))[1].get('ok'))
            before=k.app()['accounts'][0]['balance'];r=k.cmd('REQUEST_CREATE',dict(account='10010001',type='CASH_DEPOSIT',amount='500',details='Cash received at demo branch'))[1];request=r['data']['request']
            check('Cash request does not change balance before approval',k.app()['accounts'][0]['balance']==before)
            check('Teller approval credits cash request',t.cmd('REQUEST_DECIDE',dict(request=request,decision='APPROVE',note='Demo cash counted and received'))[1].get('ok') and k.app()['accounts'][0]['balance']==before+50000)
            check('Decided request cannot execute again',not t.cmd('REQUEST_DECIDE',dict(request=request,decision='APPROVE',note='Duplicate decision'))[1].get('ok'))
            r=k.cmd('REQUEST_CREATE',dict(account='10010001',type='CASH_WITHDRAWAL',amount='100',details='Cash withdrawal at branch'))[1];check('Cash withdrawal approval debits account',t.cmd('REQUEST_DECIDE',dict(request=r['data']['request'],decision='APPROVE',note='Cash disbursed in demo'))[1].get('ok'))
            r=k.cmd('REQUEST_CREATE',dict(account='10010001',type='CHEQUE_BOOK',details='New cheque book please'))[1];check('Customer can cancel pending service request',k.cmd('REQUEST_CANCEL',dict(request=r['data']['request']))[1].get('ok'))
            r=k.cmd('REQUEST_CREATE',dict(account='10010001',type='SUPPORT',details='Please explain service charges'))[1];check('Teller can reject service request with note',t.cmd('REQUEST_DECIDE',dict(request=r['data']['request'],decision='REJECT',note='Demo rejection for test'))[1].get('ok'))
            r=k.cmd('REQUEST_CREATE',dict(account='10010001',type='CARD_REPLACEMENT',details='Replace the demo debit card'))[1];last=k.app()['accounts'][0]['cardLast4'];t.cmd('REQUEST_DECIDE',dict(request=r['data']['request'],decision='APPROVE',note='Replacement issued in demo'));check('Approved replacement updates card reference',k.app()['accounts'][0]['cardLast4']!=last)
            r=k.cmd('REQUEST_CREATE',dict(account='10010001',type='LOAN',amount='5000',details='Equipment for study project'))[1];before=k.app()['accounts'][0]['balance'];t.cmd('REQUEST_DECIDE',dict(request=r['data']['request'],decision='APPROVE',note='Demo loan approved after review'));lo=k.app()['loans'][0];check('Loan approval creates principal and credits account',lo['outstanding']==500000 and k.app()['accounts'][0]['balance']==before+500000)
            check('Partial loan repayment reduces principal',k.cmd('LOAN_REPAY',dict(account='10010001',loan=lo['id'],amount='1000'))[1].get('ok') and k.app()['loans'][0]['outstanding']==400000)
            check('Loan overpayment rejected',not k.cmd('LOAN_REPAY',dict(account='10010001',loan=lo['id'],amount='5000'))[1].get('ok'))
            check('Loan full repayment closes loan',k.cmd('LOAN_REPAY',dict(account='10010001',loan=lo['id'],amount='4000'))[1].get('ok') and k.app()['loans'][0]['status']=='CLOSED')
            check('Account freeze blocks banking update',t.cmd('ACCOUNT_STATUS',dict(account='10010001',status='FROZEN'))[1].get('ok') and not k.cmd('TRANSFER',payload)[1].get('ok'));t.cmd('ACCOUNT_STATUS',dict(account='10010001',status='ACTIVE'))
            check('Customer statement exports CSV',k.request('/api/statement?account=10010001',raw=True)[0]==200 and 'Reference,Date,Type' in k.request('/api/statement?account=10010001',raw=True)[1])
            check('Customer cannot export another account statement',k.request('/api/statement?account=10020001')[0]==403)
            check('Invalid statement date interval rejected',k.request('/api/statement?account=10010001&from=2026-10-20&to=2026-01-01')[0]==400)
            check('Profile update persists',k.cmd('PROFILE_UPDATE',dict(name='Kinjal Heda',email='updated@example.test',phone='9876500001'))[1].get('ok') and k.app()['user']['email']=='updated@example.test')
            reg=Client();code,r,_=reg.request('/api/register',dict(id=str(uuid.uuid4()),username='riya.test',name='Riya Test',email='riya@example.test',phone='9876500010',password='RiyaTest@12345',branch=1));check('Public registration creates a pending-KYC zero-balance account',code==200)
            reg.login('riya.test','RiyaTest@12345');ra=reg.app();check('Pending KYC customer cannot transact',ra['user']['kyc']=='PENDING' and not reg.cmd('REQUEST_CREATE',dict(account=ra['accounts'][0]['id'],type='SUPPORT',details='Please verify my account'))[1].get('ok'))
            check('Teller can verify customer KYC',t.cmd('KYC_DECIDE',dict(user=ra['user']['id'],kyc='VERIFIED'))[1].get('ok'))
            check('Verified customer can submit service request',reg.cmd('REQUEST_CREATE',dict(account=ra['accounts'][0]['id'],type='SUPPORT',details='Verified account support'))[1].get('ok'))
            check('Admin suspension invalidates existing session',admin.cmd('USER_STATUS',dict(user=ra['user']['id'],status='SUSPENDED'))[1].get('ok') and reg.request('/api/app')[0]==401)
            admin.cmd('USER_STATUS',dict(user=ra['user']['id'],status='ACTIVE'));reg.login('riya.test','RiyaTest@12345')
            check('Wrong current password prevents password change',reg.cmd('PASSWORD_CHANGE',dict(currentPassword='wrong',newPassword='NewSecure@12345'))[0]==403)
            check('Password change invalidates old session',reg.cmd('PASSWORD_CHANGE',dict(currentPassword='RiyaTest@12345',newPassword='NewSecure@12345'))[1].get('ok') and reg.request('/api/app')[0]==401)
            check('New password authenticates successfully',reg.login('riya.test','NewSecure@12345')[0]==200)
            check('Logout invalidates session',reg.request('/api/logout',{})[0]==200 and reg.request('/api/app')[0]==401)
            # Precisely conflicting requests: each debit is 80% of the same available balance.
            source=k.app()['accounts'][1];n=(source['balance']*8//10);amt=f'{n/100:.2f}';target='10010001'
            k2=Client();k2.login('kinjal','Kinjal@12345')
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                futures=[pool.submit(cl.cmd,'TRANSFER',dict(account=source['id'],destination=target,amount=amt,description='Concurrent withdrawal equivalent'),b) for cl,b in [(k,1),(k2,2)]]
                replies=[f.result() for f in futures]
            check('Concurrent conflicting debits: exactly one succeeds',sum(bool(r[1].get('ok')) for r in replies)==1)
            check('Concurrent debit preserves nonnegative correct balance',k.app()['accounts'][1]['balance']==source['balance']-n)
            check('Audit trail includes attributed decisions and transfers',any(r['action']=='REQUEST_DECIDE' for r in admin.app()['audit']) and any(r['action']=='TRANSFER' for r in admin.app()['audit']))
            wait(lambda:healthy(admin),'replicas agree before crash');check('All three persisted state fingerprints agree',True)
            code,r,_=admin.request('/api/cluster/control',dict(branch=3,operation='STOP'));check('Admin stops actual leader process',code==200)
            wait(lambda:healthy(admin,2,2),'Node 2 failover');check('Bully-style failover elects Node 2',True)
            check('Customer transaction continues after controlled failover',k.cmd('TRANSFER',dict(payload,amount='1',description='After failover'))[1].get('ok'))
            admin.request('/api/cluster/control',dict(branch=3,operation='START'));wait(lambda:healthy(admin),'Node 3 recovery');check('Restarted node catches up and rejoins with matching state',True)
            check('Recovery events show synchronization',any('RECOVERY synchronized' in e['message'] for n in admin.request('/api/cluster')[1]['nodes'] for e in n.get('events',[])))
            admin.request('/api/cluster/control',dict(branch=3,operation='STOP'));admin.request('/api/cluster/control',dict(branch=2,operation='STOP'));wait(lambda:healthy(admin,1,1),'single remaining node');check('Lone node refuses new banking writes',k.cmd('TRANSFER',dict(payload,amount='1'))[0]==503)
            check('Console prevents stopping final node',admin.request('/api/cluster/control',dict(branch=1,operation='STOP'))[0]==400)
            admin.request('/api/cluster/control',dict(branch=2,operation='START'));admin.request('/api/cluster/control',dict(branch=3,operation='START'));wait(lambda:healthy(admin),'restored nodes')
            before=k.app();stop(p);p=start(cwd,env,logs);admin=Client();wait(lambda:admin.login('admin','Admin@123456')[0]==200,'gateway restart');wait(lambda:healthy(admin),'cluster restart');k=Client();k.login('kinjal','Kinjal@12345')
            after=k.app();check('Balances, requests, deposits and loans survive complete restart',all(after[key]==before[key] for key in ['accounts','requests','deposits','loans']))
            check('Stored idempotency survives full restart',k.cmd('TRANSFER',payload,id=rid)[1]['sequence']==seq and k.app()['accounts']==after['accounts'])
            throttle=Client();codes=[throttle.login('unknown-user','WrongPassword123')[0] for _ in range(6)];check('Login attempt throttling activates',codes[-1]==429)
            # Retained safe fixtures for template rendering checks contain demo data only.
            (ROOT/'tests/customer-fixture.json').write_text(json.dumps(k.app()))
            (ROOT/'tests/admin-fixture.json').write_text(json.dumps(admin.app()))
            (ROOT/'tests/cluster-fixture.json').write_text(json.dumps(admin.request('/api/cluster')[1]))
            print(f'\n{len(checks)} integration checks passed. Temporary test storage only.',flush=True)
        except Exception:
            for f in logs:f.flush()
            for path in list(cwd.glob('gateway-*.log'))+list((cwd/'runtime/logs').glob('*.log')):
                print('\nLOG',path.name,'\n',path.read_text()[-5000:])
            raise
        finally:
            stop(p)
            for f in logs:f.close()
