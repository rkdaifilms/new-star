(()=>{"use strict";
if(window.__NEWSTAR_AI_CHAT__)return;window.__NEWSTAR_AI_CHAT__=true;
const getDb=()=>window.supabaseClient||window.cloudClient;
const style=document.createElement("style");style.textContent=`
#ns-ai-launch{position:fixed;right:16px;bottom:calc(82px + env(safe-area-inset-bottom));z-index:10020;border:0;border-radius:999px;background:#fe2c55;color:#fff;padding:13px 16px;font-weight:700;box-shadow:0 5px 22px #0007}
#ns-ai-panel{position:fixed;right:12px;bottom:calc(140px + env(safe-area-inset-bottom));z-index:10021;width:min(360px,calc(100vw - 24px));height:min(510px,65vh);display:none;flex-direction:column;background:#171717;color:#fff;border:1px solid #444;border-radius:16px;box-shadow:0 12px 40px #0009;overflow:hidden;font:14px Arial,sans-serif}
#ns-ai-panel.open{display:flex}#ns-ai-head{display:flex;justify-content:space-between;align-items:center;padding:13px;background:#24242b}
#ns-ai-head button{background:transparent;color:#fff;border:0;font-size:20px}#ns-ai-provider-status{padding:7px 12px;background:#202027;color:#bbb;font-size:11px;border-bottom:1px solid #393941}#ns-ai-log{flex:1;overflow:auto;padding:12px;display:flex;flex-direction:column;gap:9px}
.ns-ai-msg{padding:10px 12px;border-radius:12px;max-width:88%;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.45}
.ns-ai-user{align-self:flex-end;background:#fe2c55;color:#fff}.ns-ai-bot{align-self:flex-start;background:#303039;color:#fff}
#ns-ai-form{display:flex;gap:7px;padding:10px;border-top:1px solid #393941}#ns-ai-input{flex:1;min-width:0;background:#09090d;color:#fff;border:1px solid #555;border-radius:9px;padding:11px}
#ns-ai-send{border:0;border-radius:9px;background:#fe2c55;color:#fff;padding:0 14px;font-weight:700}#ns-ai-mic{border:1px solid #666;border-radius:9px;background:#303039;color:#fff;padding:0 12px;font-size:18px}#ns-ai-mic[aria-pressed="true"]{background:#b4233c;border-color:#ff7890}#ns-ai-voice-status{font-size:11px;color:#ffc2ce;padding:0 12px 7px;min-height:14px}#ns-ai-note{font-size:11px;color:#aaa;padding:0 12px 9px}
`;document.head.appendChild(style);
const onHomeFeed=/\/index\.html?$/.test(location.pathname)||location.pathname==="/"||location.pathname.endsWith("/");
if(onHomeFeed){
 style.textContent += `
 #ns-ai-launch{position:relative;left:auto;right:auto;top:auto;bottom:auto;display:block;margin:8px 0 0 auto;padding:8px 13px;font-size:13px;z-index:25}
 #ns-ai-panel{position:relative;inset:auto;right:auto;bottom:auto;left:auto;top:auto;width:calc(100% - 24px);height:420px;max-height:65vh;margin:8px 12px 14px;z-index:25}
 `;
}
const launch=document.createElement("button");launch.id="ns-ai-launch";launch.type="button";launch.textContent="✦ New Star AI";
const panel=document.createElement("section");panel.id="ns-ai-panel";panel.setAttribute("aria-label","New Star AI Help Center");
panel.innerHTML='<div id="ns-ai-head"><strong>New Star AI · Assistant</strong><button type="button" id="ns-ai-close" aria-label="Close">×</button></div><div id="ns-ai-provider-status" role="status" aria-live="polite">AI सेवाओं की स्थिति जाँची जा रही है…</div><div id="ns-ai-log" aria-live="polite"><div class="ns-ai-msg ns-ai-bot">नमस्ते! मैं New Star AI हूँ। आप मुझसे सामान्य ज्ञान, पढ़ाई, विज्ञान, तकनीक, लेखन और रोज़मर्रा के सवाल पूछ सकते हैं। New Star के अकाउंट, वीडियो, अपलोड, लाइव, मैसेज और सुरक्षा में भी मदद कर सकता हूँ।</div></div><div id="ns-ai-note">AI replies may be inaccurate. Do not share passwords, OTPs or private financial details.</div><div id="ns-ai-voice-status" role="status" aria-live="polite"></div><form id="ns-ai-form"><input id="ns-ai-input" maxlength="2000" autocomplete="off" placeholder="कुछ भी पूछें…" required><button id="ns-ai-mic" type="button" aria-label="Start voice input" aria-pressed="false" title="Speak your message">🎙️</button><button id="ns-ai-send" type="submit">Send</button></form>';
if(onHomeFeed){const host=document.querySelector(".top-area");if(host){host.append(launch,panel);}else{document.body.append(launch,panel);}}else{document.body.append(launch,panel);}
const log=panel.querySelector("#ns-ai-log"),input=panel.querySelector("#ns-ai-input"),send=panel.querySelector("#ns-ai-send"),providerStatus=panel.querySelector("#ns-ai-provider-status");
const mic=panel.querySelector("#ns-ai-mic"),voiceStatus=panel.querySelector("#ns-ai-voice-status");
const SpeechRecognition=window.SpeechRecognition||window.webkitSpeechRecognition;
let recognition=null,recording=false,voiceBase="",voiceCaptureLocked=false;
if(!SpeechRecognition){mic.disabled=true;mic.title="Voice input is not supported in this browser";voiceStatus.textContent="इस browser में voice typing उपलब्ध नहीं है। Chrome में खोलकर देखें।";}
else{
 recognition=new SpeechRecognition();recognition.continuous=true;recognition.interimResults=true;recognition.lang=document.documentElement.lang||"hi-IN";
 recognition.onstart=()=>{recording=true;mic.setAttribute("aria-pressed","true");mic.setAttribute("aria-label","Stop voice input");mic.textContent="■";voiceStatus.textContent="सुन रहा है… बोलना पूरा होने पर Stop दबाएँ।";};
 recognition.onresult=event=>{if(voiceCaptureLocked)return;let finalText="",interimText="";for(let i=event.resultIndex;i<event.results.length;i++){const transcript=event.results[i][0].transcript;if(event.results[i].isFinal)finalText+=transcript;else interimText+=transcript;}input.value=(voiceBase+(voiceBase&&finalText?" ":"")+finalText+(interimText?" "+interimText:"")).slice(0,2000);};
 recognition.onerror=event=>{voiceStatus.textContent=event.error==="not-allowed"?"Microphone permission नहीं मिली। Browser settings में अनुमति दें।":"Voice input error: "+event.error;};
 recognition.onend=()=>{recording=false;mic.setAttribute("aria-pressed","false");mic.setAttribute("aria-label","Start voice input");mic.textContent="🎙️";if(!voiceCaptureLocked&&input.value.trim())voiceStatus.textContent="आवाज़ टेक्स्ट में बदल गई। भेजने से पहले जाँचें, फिर Send दबाएँ।"};
 mic.addEventListener("click",()=>{if(recording){recognition.stop();return;}if(!window.isSecureContext){voiceStatus.textContent="Microphone के लिए HTTPS page ज़रूरी है।";return;}voiceCaptureLocked=false;voiceBase=input.value.trim();try{recognition.start();}catch(e){voiceStatus.textContent="Mic शुरू नहीं हुआ। थोड़ी देर बाद फिर कोशिश करें।";}});
}
const history=[];
function add(text,who){const el=document.createElement("div");el.className="ns-ai-msg "+(who==="user"?"ns-ai-user":"ns-ai-bot");el.textContent=text;log.appendChild(el);log.scrollTop=log.scrollHeight;return el;}
let statusCheckedAt=0;
async function checkProviderStatus(force=false){
 if(!force&&Date.now()-statusCheckedAt<60000)return;
 statusCheckedAt=Date.now();
 const db=getDb();
 if(!db?.functions){providerStatus.textContent="AI स्थिति उपलब्ध नहीं है: Supabase कनेक्शन नहीं मिला।";return;}
 try{
  const {data:sessionData}=await db.auth.getSession();
  if(!sessionData?.session){providerStatus.textContent="AI इस्तेमाल करने के लिए पहले Login करें।";return;}
  const {data,error}=await db.functions.invoke("ai-gateway",{body:{action:"status"}});
  if(error||!data?.ok)throw error||new Error(data?.error||"Status unavailable");
  const names=[["openai","OpenAI"],["gemini","Gemini"],["openrouter","OpenRouter"]];
  const ready=names.filter(([key])=>data.providers?.[key]).map(([,label])=>label);
  providerStatus.textContent=ready.length?"AI Provider उपलब्ध: "+ready.join(" · ")+" · संदेश भेजकर Live Response जाँचें।":"AI अभी चालू नहीं है: Server पर OpenAI, Gemini या OpenRouter की API Key नहीं मिली। अधिकृत Owner Supabase → Edge Functions → Secrets में Provider Key सेट करे।";
 }catch(error){providerStatus.textContent="AI status जाँच नहीं हो सकी। Login और network connection जाँचें।";}
}
launch.addEventListener("click",()=>{panel.classList.toggle("open");if(panel.classList.contains("open"))checkProviderStatus();});panel.querySelector("#ns-ai-close").addEventListener("click",()=>panel.classList.remove("open"));
panel.querySelector("#ns-ai-form").addEventListener("submit",async e=>{
 e.preventDefault();const text=input.value.trim();if(!text||send.disabled)return;
 voiceCaptureLocked=true;if(recording&&recognition){try{recognition.stop();}catch(_){}}voiceBase="";input.value="";input.dispatchEvent(new Event("input",{bubbles:true}));voiceStatus.textContent="";
 const db=getDb();
 if(!db?.auth){add("Supabase connection अभी उपलब्ध नहीं है। पेज Reload करें; समस्या रहे तो Settings में connection status देखें।","bot");return;}
 const {data,error}=await db.auth.getSession();if(error||!data?.session){add("पहले अपने New Star account में Login करें।","bot");return;}
 add(text,"user");send.disabled=true;mic.disabled=true;send.textContent="…";
 const pending=add("जवाब तैयार हो रहा है…","bot");
 try{
  const requestMessages=[...history.slice(-8),{role:"user",content:text}];
  const {data:result,error:invokeError}=await db.functions.invoke("ai-gateway",{body:{messages:requestMessages}});
  if(invokeError){let message=invokeError.message||"AI Gateway request failed";try{const response=invokeError.context;if(response&&typeof response.clone==="function"){const payload=await response.clone().json();if(payload?.error)message=payload.error;}}catch(_){}throw new Error(message);}
  if(!result?.ok||typeof result.answer!=="string"){const attempted=Array.isArray(result?.attempted)&&result.attempted.length?" (Provider check: "+result.attempted.join(", ")+")":"";throw new Error((result?.error||"AI सेवा अभी उपलब्ध नहीं है।")+attempted);}
  pending.textContent=result.answer;
  history.push({role:"user",content:text},{role:"assistant",content:result.answer});
  if(history.length>10)history.splice(0,history.length-10);
 }catch(err){
  let detail=err?.message||"AI सेवा अभी उपलब्ध नहीं है।";
  try{const response=err?.context;if(response&&typeof response.json==="function"){const payload=await response.clone().json();if(payload?.error)detail=payload.error;}}
  catch(_){}
  pending.textContent=detail.includes("No AI provider is configured")?"AI अभी चालू नहीं है क्योंकि Server पर कोई Provider API Key configured नहीं है। अधिकृत Owner को Supabase Edge Function Secrets में OpenAI, Gemini या OpenRouter की Key जोड़नी होगी।":detail+" कृपया थोड़ी देर बाद फिर कोशिश करें।";
 }
 finally{send.disabled=false;mic.disabled=!SpeechRecognition;send.textContent="Send";voiceCaptureLocked=false;input.value="";input.dispatchEvent(new Event("input",{bubbles:true}));input.focus();}
});
})();