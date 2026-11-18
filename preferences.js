(()=>{"use strict";
const KEY="newstar_preferences";
const themes={
dark:["#080808","#171717","#ffffff","#aaaaaa","#fe2c55"],light:["#f4f6fa","#ffffff","#171923","#5d6472","#d92350"],
ocean:["#071923","#102b3a","#f4fbff","#a8c1cc","#25b8d0"],forest:["#101b14","#1b2b20","#f4fff6","#afc5b3","#39a96b"],
sunset:["#21130e","#3b2118","#fff4ed","#d9a88d","#ff874d"],purple:["#160e28","#281943","#f7f0ff","#c2addb","#b58cff"],
rose:["#260f1b","#3b1b2b","#fff1f6","#d9a9bc","#ff6f9e"],crimson:["#210b10","#39131d","#fff1f3","#d7a1aa","#ff405d"],
gold:["#211b0b","#382d12","#fff8df","#d5c28a","#e9b949"],teal:["#08201e","#103532","#eafffb","#a1cbc4","#26c6b5"],
cyan:["#071c27","#103344","#edfbff","#a6cbd8","#48d5f5"],indigo:["#10132c","#1b2250","#f0f2ff","#aeb6df","#7d8cff"],
lavender:["#1d1730","#30264a","#f8f2ff","#c8b8df","#c2a0ff"],mint:["#0d211b","#18372c","#edfff6","#add2bf","#62d6a2"],
emerald:["#071d16","#103526","#edfff6","#a3cbb7","#28c985"],ruby:["#220c1c","#3b1530","#fff0fa","#d7a5c6","#f34e9b"],
amber:["#211608","#3b2810","#fff6e8","#d6bd91","#ffb02e"],coral:["#24120f","#3c211c","#fff3ef","#d9aaa0","#ff806e"],
slate:["#111820","#202b36","#f3f6fa","#aebbc8","#8ba8c4"],coffee:["#1b120d","#302117","#fff5ec","#c9ae98","#c58b5a"],
plum:["#1e1021","#351d39","#fff1ff","#c7a9cb","#c17acb"],sakura:["#27151d","#432632","#fff3f7","#d9b2c0","#ff91b8"],
neon:["#080d16","#121d2b","#f4ffff","#a5c2c7","#4dffdb"],sand:["#211d14","#383124","#fff9e9","#d1c4a4","#d8a85d"],
aurora:["#0c1720","#192b38","#f2ffff","#a8c7d0","#7ee0c3"]};
function read(){try{const v=JSON.parse(localStorage.getItem(KEY)||"{}");return v&&typeof v==="object"?v:{}}catch(_){return {}}}
function apply(){
 const p=read(),t=themes[p.theme]||themes.dark,light=(p.theme||"dark")==="light",root=document.documentElement;
 root.lang=p.language||"hi";root.dir=["ar","ur","fa"].includes(p.language)?"rtl":"ltr";root.style.colorScheme=light?"light":"dark";
 let style=document.getElementById("newstar-preference-style");
 if(!style){style=document.createElement("style");style.id="newstar-preference-style";document.head.appendChild(style)}
 style.textContent=`
 :root{--ns-bg:${t[0]};--ns-panel:${t[1]};--ns-text:${t[2]};--ns-muted:${t[3]};--ns-accent:${t[4]};--bg:${t[0]};--panel:${t[1]};--text:${t[2]};--muted:${t[3]};--accent:${t[4]}}
 body{background-color:var(--ns-bg)!important;color:var(--ns-text)!important}
 header,.header,.top,.intro,.panel,.card,.section,.settings-card,.setting-row,.modal-content,.menu,.row,.quick a{color:var(--ns-text)}
 .panel,.card,.settings-card,.setting-row,.modal-content,.menu,.quick a{background-color:var(--ns-panel)!important;border-color:var(--ns-muted)!important}
 input,select,textarea{background-color:var(--ns-bg)!important;color:var(--ns-text)!important;border-color:var(--ns-muted)!important}
 .muted,.small-note,.setting-row p,.section-title,.note{color:var(--ns-muted)!important}
 button:not(.danger):not(.secondary):not(.newstar-plus-icon),.primary-button,.save-button{background-color:var(--ns-accent)!important;border-color:var(--ns-accent)!important;color:#fff!important}
 button.secondary,.action.secondary{background-color:var(--ns-panel)!important;color:var(--ns-text)!important;border-color:var(--ns-muted)!important}
 #newstar-global-nav{background:var(--ns-panel)!important}
 #newstar-global-nav a.active{color:var(--ns-accent)!important}
 #newstar-menu-panel{background:var(--ns-panel)!important;color:var(--ns-text)!important}
 #newstar-menu-panel a,#newstar-menu-panel button{color:var(--ns-text)!important}`;
 window.NewStarI18n?.apply();
}
let syncBusy=false,syncPending=false,syncPendingWrite=false;
async function sync(preferLocal=false){
 const db=window.supabaseClient||window.cloudClient;
 if(!db?.auth)return false;
 if(syncBusy){syncPending=true;syncPendingWrite=syncPendingWrite||preferLocal;return false;}
 syncBusy=true;
 try{
  const {data:{user},error:authError}=await db.auth.getUser();
  if(authError||!user)return false;
  if(preferLocal){
   const {error:saveError}=await db.from("user_preferences").upsert({user_id:user.id,preferences:read()},{onConflict:"user_id"});
   if(saveError)throw saveError;
   return true;
  }
  const {data:remote,error}=await db.from("user_preferences").select("preferences").eq("user_id",user.id).maybeSingle();
  if(error)throw error;
  if(remote?.preferences&&typeof remote.preferences==="object"){
   localStorage.setItem(KEY,JSON.stringify(remote.preferences));apply();window.dispatchEvent(new Event("newstar:preferences-loaded"));return true;
  }
  if(localStorage.getItem(KEY)){
   const {error:saveError}=await db.from("user_preferences").upsert({user_id:user.id,preferences:read()},{onConflict:"user_id"});
   if(saveError)throw saveError;
  }
  return true;
 }catch(error){console.warn("New Star preferences cloud sync unavailable:",error?.message||error);return false}
 finally{
  syncBusy=false;
  if(syncPending){const write=syncPendingWrite;syncPending=false;syncPendingWrite=false;queueMicrotask(()=>sync(write));}
 }
}
window.NewStarPreferences={apply,get:read,set(patch){const next={...read(),...patch};localStorage.setItem(KEY,JSON.stringify(next));apply();sync(true);return next},sync};
apply();
window.addEventListener("storage",e=>{if(e.key===KEY){apply();sync(true)}});
window.addEventListener("newstar:preferences-changed",()=>sync(true));
if(document.readyState==="loading")document.addEventListener("DOMContentLoaded",sync,{once:true});else sync();
window.addEventListener("focus",()=>{if(document.visibilityState==="visible")sync()});
})();