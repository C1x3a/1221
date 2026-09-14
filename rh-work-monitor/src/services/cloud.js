const https = require('https');

const CLOUD = process.env.RH_CLOUD || 'https://rh-work-cloud-web-production.up.railway.app';

function post(path,data){
 return new Promise((resolve,reject)=>{
  const req=https.request(CLOUD+path,{method:'POST',headers:{'content-type':'application/json'}},res=>{
   let body='';res.on('data',d=>body+=d);res.on('end',()=>resolve(body));
  });
  req.on('error',reject);
  req.write(JSON.stringify(data));
  req.end();
 });
}

async function heartbeat(){
 return post('/api/local-agent/heartbeat',{
  hostname:require('os').hostname(),
  version:'desktop-0.1',
  browser:'edge',
  timestamp:new Date().toISOString()
 });
}

module.exports={heartbeat};
