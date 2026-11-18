import {ref} from 'vue'
import {getToken} from './auth'
export {permissionFor} from './permission-policy.js'
export const staffGrants=ref([])
let cachedToken='',checkedAt=0
export function isDemoSession(){const token=getToken();return Boolean(token && cachedToken===token && staffGrants.value.includes('demo.read'))}
const pending = new Map()
export function can(code){return staffGrants.value.includes(code)||(code!=='order.refund'&&staffGrants.value.includes('*'))}
export async function loadStaffPermissions(force=false){
 const token=getToken();if(cachedToken!==token){staffGrants.value=[];cachedToken='';checkedAt=0}if(!token)return
 if(pending.has(token))return pending.get(token)
 if(!force&&cachedToken===token&&Date.now()-checkedAt<30000)return
 const task=(async()=>{
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);let response
 try{response=await fetch((import.meta.env.VITE_API_BASE_URL||'/api')+'/cs/permissions',{headers:{Authorization:'Bearer '+token},signal:controller.signal})}finally{clearTimeout(timer)}
 const result=await response.json();if(!response.ok||result.code!==200)throw Object.assign(new Error(result.msg||'读取角色权限失败'),{code:result.code||response.status})
 if(getToken()!==token)return loadStaffPermissions()
 staffGrants.value=result.data;cachedToken=token;checkedAt=Date.now()
 })().finally(()=>pending.delete(token))
 pending.set(token,task)
 return task
}
