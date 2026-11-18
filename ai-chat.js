(()=>{"use strict";
if(window.__NEWSTAR_AI_CHAT__)return;window.__NEWSTAR_AI_CHAT__=true;
const getDb=()=>window.supabaseClient||window.cloudClient;
const style=document.createElement("style");style.textContent=`
#ns-ai-launch{position:fixed;right:16px;bottom:calc(82px + env(safe-area-inset-bottom));z-index:10020;border:0;border-radius:999px;background:#fe2c55;color:#fff;padding:13px 16px;font-weight:700;box-shadow:0 5px 22px #0007}
#ns-ai-panel{position:fixed;right:12px;bottom:calc(140px + env(safe-area-inset-bottom));z-index:10021;width:min(360px,calc(100vw - 24px));height:min(510px,65vh);display:none;flex-direction:column;background:#171717;color:#fff;border:1px solid #444;border-radius:16px;box-shadow:0 12px 40px #0009;overflow:hidden;font:14px Arial,sans-serif}
#ns-ai-panel.open{display:flex}#ns-ai-head{display:flex;justify-content:space-between;align-items:center;padding:13px;background:#24242b}
#ns-ai-head button{background:transparent;color:#fff;border:0;font-size:20px}#ns-ai-log{flex:1;overflow:auto;padding:12px;display:flex;flex-direction:column;gap:9px}
.ns-ai-msg{padding:10px 12px;border-radius:12px;max-width:88%;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.45}
.ns-ai-user{align-self:flex-end;background:#fe2c55;color:#fff}.ns-ai-bot{align-self:flex-start;background:#303039;color:#fff}
#ns-ai-form{display:flex;gap:7px;padding:10px;border-top:1px solid #393941}#ns-ai-input{flex:1;min-width:0;background:#09090d;color:#fff;border:1px solid #555;border-radius:9px;padding:11px}
#ns-ai-send{border:0;border-radius:9px;background:#fe2c55;color:#fff;padding:0 14px;font-weight:700}#ns-ai-note{font-size:11px;color:#aaa;padding:0 12px 9px}
`;document.head.appendChild(style);
const launch=document.createElement("button");launch.id="ns-ai-launch";launch.type="button";launch.textContent="✦ AI Help";
const panel=document.createElement("section");panel.id="ns-ai-panel";panel.setAttribute("aria-label","AI assistant");
panel.innerHTML='<div id="ns-ai-head"><strong>New Star AI Assistant</strong><button type="button" id="ns-ai-close" aria-label="Close">×</button></div><div id="ns-ai-log" aria-live="polite"><div class="ns-ai-msg ns-ai-bot">नमस्ते! New Star के उपयोग में मदद चाहिए तो पूछें।</div></div><div id="ns-ai-note">AI replies may be inaccurate. Do not share passwords, OTPs or private financial details.</div><form id="ns-ai-form"><input id="ns-ai-input" maxlength="2000" autocomplete="off" placeholder="अपना सवाल लिखें…" required><button id="ns-ai-send" type="submit">Send</button></form>';
document.body.append(launch,panel);
const log=panel.querySelector("#ns-ai-log"),input=panel.querySelector("#ns-ai-input"),send=panel.querySelector("#ns-ai-send");
const history=[];
function add(text,who){const el=document.createElement("div");el.className="ns-ai-msg "+(who==="user"?"ns-ai-user":"ns-ai-bot");el.textContent=text;log.appendChild(el);log.scrollTop=log.scrollHeight;return el;}
launch.addEventListener("click",()=>panel.classList.toggle("open"));panel.querySelector("#ns-ai-close").addEventListener("click",()=>panel.classList.remove("open"));
panel.querySelector("#ns-ai-form").addEventListener("submit",async e=>{
 e.preventDefault();const text=input.value.trim();if(!text||send.disabled)return;
 const db=getDb();
 if(!db?.auth){add("Supabase connection अभी उपलब्ध नहीं है। पेज Reload करें; समस्या रहे तो Settings में connection status देखें।","bot");return;}
 const {data,error}=await db.auth.getSession();if(error||!data?.session){add("पहले अपने New Star account में Login करें।","bot");return;}
 add(text,"user");input.value="";send.disabled=true;send.textContent="…";
 const pending=add("जवाब तैयार हो रहा है…","bot");
 try{
  const requestMessages=[...history.slice(-8),{role:"user",content:text}];
  const {data:result,error:invokeError}=await db.functions.invoke("ai-gateway",{body:{messages:requestMessages}});
  if(invokeError)throw invokeError;
  if(!result?.ok||typeof result.answer!=="string"){const attempted=Array.isArray(result?.attempted)&&result.attempted.length?" (जाँच: "+result.attempted.join(", ")+")":"";throw new Error((result?.error||"AI सेवा अभी उपलब्ध नहीं है।")+attempted);}
  pending.textContent=result.answer;
  history.push({role:"user",content:text},{role:"assistant",content:result.answer});
  if(history.length>10)history.splice(0,history.length-10);
 }catch(err){pending.textContent=err?.message||"AI सेवा अभी उपलब्ध नहीं है। Provider setup और connection जाँचें।";}
 finally{send.disabled=false;send.textContent="Send";input.focus();}
});
})();