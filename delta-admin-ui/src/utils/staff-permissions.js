import {ref} from 'vue'
import {getToken} from './auth'
export const staffGrants=ref([])
let cachedToken='',checkedAt=0
export function can(code){return staffGrants.value.includes(code)||(code!=='order.refund'&&staffGrants.value.includes('*'))}
export async function loadStaffPermissions(force=false){
 const token=getToken();if(!token){staffGrants.value=[];cachedToken='';return}
 if(!force&&cachedToken===token&&Date.now()-checkedAt<30000)return
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);let response
 try{response=await fetch((import.meta.env.VITE_API_BASE_URL||'/api')+'/cs/permissions',{headers:{Authorization:'Bearer '+token},signal:controller.signal})}finally{clearTimeout(timer)}
 const result=await response.json();if(!response.ok||result.code!==200)throw Object.assign(new Error(result.msg||'读取角色权限失败'),{code:result.code||response.status})
 staffGrants.value=result.data;cachedToken=token;checkedAt=Date.now()
}
export function permissionFor(path){
 if(/^\/system(?:\/|$)/.test(path))return 'system.manage'
 if(/^\/order\//.test(path))return 'order.read'
 if(path==='/player'||path==='/player-level')return 'player.manage'
 if(/^\/user\//.test(path))return 'user.read'
 if(/^\/statistics\//.test(path)||path==='/settlements')return 'finance.read'
 if(path==='/withdraw')return 'withdraw.review'
 return null
}
