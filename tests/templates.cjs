// Executes every screen template against fixtures produced by real integration tests.
// This checks runtime rendering and escaping; it is not a substitute for browser visual QA.
const vm=require('node:vm'),fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'..');
const nodes=new Map();
const query=s=>{if(!nodes.has(s))nodes.set(s,{innerHTML:'',value:'',children:[],classList:{toggle(){}}});return nodes.get(s);};
const session=new Map();
const context=vm.createContext({console,Intl,Date,Set,Map,Object,Number,String,Math,JSON,URLSearchParams,Error,crypto:require('node:crypto').webcrypto,sessionStorage:{getItem:k=>session.get(k)||null,setItem:(k,v)=>session.set(k,v)},location:{hash:''},window:{addEventListener(){},scrollTo(){}},document:{querySelector:query,querySelectorAll:()=>[],addEventListener(){},activeElement:{tagName:'BODY'}},setInterval(){},setTimeout(){},fetch:async()=>({ok:false,status:401,json:async()=>({error:'Sign in'})})});
for(const name of ['core.js','shell.js','pages/customer.js','pages/staff.js','controllers.js'])vm.runInContext(fs.readFileSync(path.join(root,'web/assets',name),'utf8'),context,{filename:name});
let checks=0;
function run(label,code){vm.runInContext(code,context);const html=query('#root').innerHTML;if(!html||html.includes('undefined'))throw Error(label+': missing or undefined content');if(html.includes('<img src=x onerror='))throw Error('Unsafe escaping');console.log('PASS:',label);checks++;}
const customer=JSON.parse(fs.readFileSync(path.join(__dirname,'customer-fixture.json')));
const admin=JSON.parse(fs.readFileSync(path.join(__dirname,'admin-fixture.json')));
const cluster=JSON.parse(fs.readFileSync(path.join(__dirname,'cluster-fixture.json')));
run('Customer sign-in template','register=false;renderAuth()');
run('Account registration template','register=true;renderAuth()');
context.fixture=customer;
for(const page of ['overview','transfer','payments','cards','savings','loans','statements','services','settings'])run('Customer '+page,`app=fixture;page='${page}';renderShell()`);
context.fixture=admin;context.clusterFixture=cluster;
for(const page of ['workbench','customers','requests','statements','audit','operations','settings'])run('Administrator '+page,`app=fixture;cluster=clusterFixture;page='${page}';renderShell()`);
context.fixture={...admin,user:{...admin.user,role:'TELLER'}};
run('Teller navigation excludes DCS controls',"app=fixture;page='workbench';renderShell();if(document.querySelector('#root').innerHTML.includes('href=\"#operations\"'))throw Error('Teller DCS navigation exposed')");
context.fixture={...customer,user:{...customer.user,name:'<img src=x onerror=alert(1)> Test'}};
run('Customer-controlled text is escaped',"app=fixture;page='overview';renderShell()");
vm.runInContext("app=fixture;receiptModal(app.transactions[0])",context);if(!query('#modal-root').innerHTML.includes('Transaction receipt'))throw Error('Receipt did not render');checks++;console.log('PASS: Transaction receipt template');
console.log(`\n${checks} template checks passed. Browser layout and interactions still require a browser environment.`);
