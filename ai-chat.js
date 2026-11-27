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
 /* Universal full-screen layout: phones, tablets, laptops and desktops */
 #ns-ai-panel,#ns-ai-panel.open{position:fixed!important;inset:0!important;right:0!important;bottom:0!important;left:0!important;top:0!important;width:100vw!important;width:100dvw!important;height:100vh!important;height:100dvh!important;max-height:none!important;min-height:0!important;margin:0!important;border:0!important;border-radius:0!important;z-index:2147483001!important;box-shadow:none!important;padding-top:env(safe-area-inset-top);padding-bottom:env(safe-area-inset-bottom);box-sizing:border-box!important}
 #ns-ai-head{position:relative;flex:0 0 auto;min-height:58px;padding:10px 14px;background:#202027;border-bottom:1px solid #393941;box-sizing:border-box}
 #ns-ai-head button{width:44px;height:44px;min-width:44px;border-radius:50%;background:#383843;font-size:28px;line-height:1}
 #ns-ai-provider-status{flex:0 0 auto}
 #ns-ai-log{flex:1 1 auto;min-height:0;overflow-y:auto;overscroll-behavior:contain}
 #ns-ai-note,#ns-ai-voice-status{flex:0 0 auto}
 #ns-ai-form{flex:0 0 auto;padding:10px 12px calc(10px + env(safe-area-inset-bottom));background:#171717;box-sizing:border-box}
 #ns-ai-input{font-size:16px;min-width:0}
 @supports not (height:100dvh){#ns-ai-panel,#ns-ai-panel.open{height:100vh!important;width:100vw!important}}

 `;
}

/* Help Center AI opens as a true full-screen workspace on every app page. */
style.textContent += `
#ns-ai-panel,#ns-ai-panel.open{position:fixed!important;inset:0!important;right:0!important;bottom:0!important;left:0!important;top:0!important;width:100vw!important;width:100dvw!important;height:100vh!important;height:100dvh!important;max-height:none!important;min-height:0!important;margin:0!important;border:0!important;border-radius:0!important;z-index:2147483001!important;box-shadow:none!important;padding-top:env(safe-area-inset-top);padding-bottom:env(safe-area-inset-bottom);box-sizing:border-box!important}
#ns-ai-head{position:relative;flex:0 0 auto;min-height:58px;padding:10px 14px;background:#202027;border-bottom:1px solid #393941;box-sizing:border-box}
#ns-ai-head button{width:44px;height:44px;min-width:44px;border-radius:50%;background:#383843;font-size:28px;line-height:1}
#ns-ai-provider-status{flex:0 0 auto}
#ns-ai-log{flex:1 1 auto;min-height:0;overflow-y:auto;overscroll-behavior:contain}
#ns-ai-note,#ns-ai-voice-status{flex:0 0 auto}
#ns-ai-form{flex:0 0 auto;padding:10px 12px calc(10px + env(safe-area-inset-bottom));background:#171717;box-sizing:border-box}
#ns-ai-input{font-size:16px;min-width:0}
`;
const launch=document.createElement("button");launch.id="ns-ai-launch";launch.type="button";launch.textContent="✦ New Star Help AI";
const panel=document.createElement("section");panel.id="ns-ai-panel";panel.setAttribute("aria-label","New Star AI Help Center");
panel.innerHTML='<div id="ns-ai-head"><strong>New Star Help Center AI</strong><button type="button" id="ns-ai-close" aria-label="Close">×</button></div><div id="ns-ai-provider-status" role="status" aria-live="polite">AI सर्वर की स्थिति जाँची जा रही है…</div><div id="ns-ai-log" aria-live="polite"><div class="ns-ai-msg ns-ai-bot">नमस्ते! मैं New Star Help Center AI हूँ। मैं New Star अकाउंट, लॉगिन, प्रोफ़ाइल, वीडियो अपलोड/प्लेबैक, लाइव, मैसेज, सेटिंग्स, रिपोर्ट, सुरक्षा और अपील से जुड़ी सहायता कर सकता हूँ। रचनात्मक लेखन, कहानी, स्क्रिप्ट, थंबनेल, इमेज और कोडिंग के लिए होम पेज का New Star AI Engine खोलें।</div></div><div id="ns-ai-note">AI replies may be inaccurate. Do not share passwords, OTPs or private financial details.</div><div id="ns-ai-voice-status" role="status" aria-live="polite"></div><form id="ns-ai-form"><input id="ns-ai-input" maxlength="2000" autocomplete="off" placeholder="कुछ भी पूछें…" required><button id="ns-ai-mic" type="button" aria-label="Start voice input" aria-pressed="false" title="Speak your message">🎙️</button><button id="ns-ai-send" type="submit">Send</button></form>';
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
const chatHistory=[];
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
  const ready=data.providers?.gemini?"Gemini":"";
  providerStatus.textContent=ready?"Help & Support AI: Gemini configured. Home New Star AI uses its separate private engine.":"Help & Support AI के लिए Gemini server key configured नहीं है। अधिकृत project owner Supabase Edge Function Secrets में Gemini key जाँचे।";
 }catch(error){providerStatus.textContent="AI status जाँच नहीं हो सकी। Login और network connection जाँचें।";}
}
let aiHistoryEntry=false;const previousBodyOverflow=document.body.style.overflow;
function openAiPanel(){if(panel.classList.contains("open"))return;panel.classList.add("open");document.body.style.overflow="hidden";if(!aiHistoryEntry){try{window.history.pushState({...(window.history.state||{}),nsAiOpen:true},"","#new-star-ai");aiHistoryEntry=true;}catch(_){}}checkProviderStatus();}
function closeAiPanel(fromPop=false){if(!panel.classList.contains("open"))return;panel.classList.remove("open");document.body.style.overflow=previousBodyOverflow;if(recording&&recognition){voiceCaptureLocked=true;try{recognition.stop();}catch(_){}}if(aiHistoryEntry){aiHistoryEntry=false;if(!fromPop&&window.history.state?.nsAiOpen){try{window.history.back();}catch(_){}}}}
window.NewStarHelpAI={open:openAiPanel,close:()=>closeAiPanel(false)};
document.dispatchEvent(new CustomEvent("newstar:help-ai-ready"));
launch.addEventListener("click",openAiPanel);panel.querySelector("#ns-ai-close").addEventListener("click",()=>closeAiPanel(false));
window.addEventListener("popstate",()=>{if(panel.classList.contains("open"))closeAiPanel(true);});
window.addEventListener("keydown",e=>{if(e.key==="Escape"&&panel.classList.contains("open"))closeAiPanel(false);});
panel.querySelector("#ns-ai-form").addEventListener("submit",async e=>{
 e.preventDefault();const text=input.value.trim();if(!text||send.disabled)return;
 voiceCaptureLocked=true;if(recording&&recognition){try{recognition.stop();}catch(_){}}voiceBase="";input.value="";input.dispatchEvent(new Event("input",{bubbles:true}));voiceStatus.textContent="";
 const db=getDb();
 if(!db?.auth){add("Supabase connection अभी उपलब्ध नहीं है। पेज Reload करें; समस्या रहे तो Settings में connection status देखें।","bot");return;}
 const {data,error}=await db.auth.getSession();if(error||!data?.session){add("पहले अपने New Star account में Login करें।","bot");return;}
 add(text,"user");send.disabled=true;mic.disabled=true;send.textContent="…";
 const pending=add("जवाब तैयार हो रहा है…","bot");
 try{
  const requestMessages=[...chatHistory.slice(-8),{role:"user",content:text}];
  const {data:result,error:invokeError}=await db.functions.invoke("ai-gateway",{body:{messages:requestMessages,mode:"help"}});
  if(invokeError){let message=invokeError.message||"AI Gateway request failed";try{const response=invokeError.context;if(response&&typeof response.clone==="function"){const payload=await response.clone().json();if(payload?.error)message=payload.error;}}catch(_){}throw new Error(message);}
  if(!result?.ok||typeof result.answer!=="string"){const attempted=Array.isArray(result?.attempted)&&result.attempted.length?" (Provider check: "+result.attempted.join(", ")+")":"";throw new Error((result?.error||"AI सेवा अभी उपलब्ध नहीं है।")+attempted);}
  pending.textContent=result.answer;
  chatHistory.push({role:"user",content:text},{role:"assistant",content:result.answer});
  if(chatHistory.length>10)chatHistory.splice(0,chatHistory.length-10);
 }catch(err){
  let detail=err?.message||"AI सेवा अभी उपलब्ध नहीं है।";
  try{const response=err?.context;if(response&&typeof response.json==="function"){const payload=await response.clone().json();if(payload?.error)detail=payload.error;}}
  catch(_){}
  pending.textContent=detail+" कृपया थोड़ी देर बाद फिर कोशिश करें।";
 }
 finally{send.disabled=false;mic.disabled=!SpeechRecognition;send.textContent="Send";voiceCaptureLocked=false;input.value="";input.dispatchEvent(new Event("input",{bubbles:true}));input.focus();}
});
})();