const API='https://api.tejdux.com'
const S=Date.now().toString().slice(-7)
let r=await fetch(API+'/api/auth/signup',{method:'POST',headers:{'Content-Type':'application/json'},
  body:JSON.stringify({email:`h.${S}@tejdux.test`,password:'DemoPass123!',brandName:'H',acceptedTerms:true})})
const a=await r.json(); const H={Authorization:'Bearer '+a.accessToken,'Content-Type':'application/json'}
for (const pf of ['instagram','tiktok']) {
  const x=await fetch(API+'/api/creators/resolve-handle',{method:'POST',headers:H,body:JSON.stringify({handle:'glow_daily',platform:pf})})
  const j=await x.json()
  console.log(pf, x.status, 'resolved='+j.resolved, 'metricsSource='+(j.metricsSource||'-'))
}
