(()=>{"use strict";
const $=id=>document.getElementById(id);
const form=$("ns-form"),prompt=$("ns-prompt"),output=$("ns-output"),submit=$("ns-submit"),status=$("engine-status"),foot=$("ns-footnote"),fileInput=$("ns-file");
let mode="chat",busy=false,attachment=null,conversationId=localStorage.getItem("new-star-ai-conversation")||"",recorder=null,recordedChunks=[];
const cfg=window.NEW_STAR_AI_CONFIG||{};
const base=(cfg.apiBase||"").replace(/\/+$/,"");
const modes={chat:["AI Chat","Ask questions, plan and create."],research:["Live Research","Search the web and return source links."],image:["Image Generation","Describe an image and choose output size."],video:["Video Generation","Describe a video or attach an image to animate."]};
const routes={chat:"/api/new-star-ai/chat",research:"/api/new-star-ai/research",image:"/api/new-star-ai/images",video:"/api/new-star-ai/videos"};
function bubble(text,kind="ai"){const el=document.createElement("div");el.className="ns-message "+(kind==="user"?"ns-user":"ns-ai");el.textContent=text;output.append(el);output.scrollTop=output.scrollHeight;return el;}
function toBase64(blob){return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(",")[1]||"");reader.onerror=()=>reject(new Error("Could not read voice recording."));reader.readAsDataURL(blob);});}
async function transcribeVoice(blob){const client=window.supabaseClient||window.cloudClient;if(!client?.functions)throw new Error("New Star AI connection is not ready. Reload the page.");const mime=(blob.type||"audio/webm").split(";")[0];const audio=await toBase64(blob);const result=await client.functions.invoke("ai-gateway",{body:{action:"transcribe",audio,mimeType:mime}});if(result.error||!result.data?.ok)throw new Error(result.data?.error||"Voice transcription failed.");prompt.value=(prompt.value.trim()?prompt.value.trim()+" ":"")+result.data.transcript;prompt.focus();bubble("Voice transcript: "+result.data.transcript,"user");}
async function toggleVoice(){const mic=$("ns-mic");if(recorder&&recorder.state==="recording"){recorder.stop();mic.textContent="Processing…";mic.disabled=true;return;}if(!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder){bubble("Voice recording is not supported in this browser. Try an updated Chrome browser.","ai");return;}try{const stream=await navigator.mediaDevices.getUserMedia({audio:true});const preferred=["audio/webm;codecs=opus","audio/webm","audio/mp4"].find(type=>MediaRecorder.isTypeSupported(type));recorder=preferred?new MediaRecorder(stream,{mimeType:preferred}):new MediaRecorder(stream);recordedChunks=[];recorder.ondataavailable=e=>{if(e.data?.size)recordedChunks.push(e.data);};recorder.onerror=()=>{stream.getTracks().forEach(t=>t.stop());mic.disabled=false;mic.textContent="🎙 Voice";bubble("Voice recording failed. Check microphone permission and try again.","ai");};recorder.onstop=async()=>{stream.getTracks().forEach(t=>t.stop());try{const blob=new Blob(recordedChunks,{type:recorder.mimeType||"audio/webm"});if(blob.size<100)throw new Error("Recording was too short. Please try again.");await transcribeVoice(blob);}catch(error){bubble(error.message||"Voice transcription failed.","ai");}finally{mic.disabled=false;mic.textContent="🎙 Voice";recorder=null;}};recorder.start();mic.textContent="■ Stop recording";}catch(error){mic.disabled=false;mic.textContent="🎙 Voice";bubble(error.name==="NotAllowedError"?"Microphone permission was denied. Allow microphone access in browser settings.":error.message||"Could not start microphone.","ai");}}
async function speakText(text,button){const client=window.supabaseClient||window.cloudClient;if(!client?.functions){bubble("New Star AI connection is not ready.","ai");return;}button.disabled=true;const old=button.textContent;let audioUrl="";let audioElement=null;const cleanup=()=>{if(audioUrl){URL.revokeObjectURL(audioUrl);audioUrl="";}button.textContent=old;button.disabled=false;};button.textContent="Preparing voice…";try{const result=await client.functions.invoke("ai-gateway",{body:{action:"speak",text}});if(result.error||!result.data?.ok)throw new Error(result.data?.error||"AI voice is unavailable.");if(typeof result.data.audio!=="string"||!result.data.audio)throw new Error("AI voice returned no audio data.");const binary=atob(result.data.audio);const bytes=new Uint8Array(binary.length);for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);const blob=new Blob([bytes],{type:result.data.mimeType||"audio/mpeg"});audioUrl=URL.createObjectURL(blob);audioElement=new Audio(audioUrl);button.textContent="Playing…";audioElement.onended=cleanup;audioElement.onerror=cleanup;await audioElement.play();}catch(error){cleanup();bubble(error.message||"Voice playback failed.","ai");}}
function setMode(next){mode=next;document.querySelectorAll("[data-mode]").forEach(b=>b.classList.toggle("active",b.dataset.mode===mode));$("mode-title").textContent=modes[mode][0];$("mode-help").textContent=modes[mode][1];prompt.placeholder=mode==="image"?"Describe the image you want to generate…":mode==="video"?"Describe the video you want to create…":"Ask anything…";$("ns-size").hidden=mode!=="image";$("ns-upload").hidden=mode!=="chat";$("ns-mic").hidden=mode!=="chat";submit.textContent=mode==="image"?"Generate image ↗":mode==="video"?"Generate video ↗":"Send ↗";}
document.querySelectorAll("[data-mode]").forEach(b=>b.addEventListener("click",()=>{if(!busy)setMode(b.dataset.mode);}));
fileInput.addEventListener("change",()=>{attachment=fileInput.files?.[0]||null;$("ns-attachment-wrap").hidden=!attachment;$("ns-attachment-name").textContent=attachment?attachment.name:"";if(attachment&&attachment.size>6*1024*1024){attachment=null;fileInput.value="";$("ns-attachment-wrap").hidden=true;bubble("Image must be smaller than 6 MB.","ai");}});
$("ns-clear-file").addEventListener("click",()=>{attachment=null;fileInput.value="";$("ns-attachment-wrap").hidden=true;});
async function getSession(){const client=window.supabaseClient||window.cloudClient||window.supabase;if(!client?.auth)return null;const{data}=await client.auth.getSession();return data?.session||null;}
function mediaUrl(value){try{const u=new URL(value,location.href);return ["https:","http:"].includes(u.protocol)?u.href:null;}catch{return null;}}
async function pollJob(jobId,token,pending){const pollPath=cfg.jobPath||"/api/new-star-ai/jobs/";const deadline=Date.now()+(cfg.jobTimeoutMs||600000);while(Date.now()<deadline){await new Promise(r=>setTimeout(r,cfg.pollIntervalMs||2500));if(abortController?.signal.aborted)throw new Error("Request cancelled.");const r=await fetch(base+pollPath+encodeURIComponent(jobId),{headers:{Authorization:"Bearer "+token},signal:abortController.signal});const d=await r.json().catch(()=>({}));if(!r.ok)throw new Error(d.error||"Could not check generation status ("+r.status+").");if(d.status==="failed"||d.status==="error")throw new Error(d.error||"Generation failed.");if(d.status==="completed"||d.status==="succeeded")return d;pending.textContent=d.message||("Generation in progress"+(d.progress!=null?" · "+d.progress+"%":"")+"…");}throw new Error("Generation is taking longer than expected. You can retry; the server job may still be running.");}
function renderResult(data,pending){pending.textContent=data.answer||data.message||"Completed.";const image=mediaUrl(data.imageUrl||data.image_url);if(image){const img=document.createElement("img");img.alt="AI-generated image";img.loading="lazy";img.src=image;pending.append(img);}const video=mediaUrl(data.videoUrl||data.video_url);if(video){const v=document.createElement("video");v.controls=true;v.playsInline=true;v.src=video;v.style.cssText="width:100%;max-width:640px;border-radius:12px;margin-top:10px";pending.append(v);}if(Array.isArray(data.sources)&&data.sources.length){const list=document.createElement("div");for(const s of data.sources){const url=mediaUrl(s?.url);if(!url)continue;const a=document.createElement("a");a.href=url;a.target="_blank";a.rel="noopener noreferrer";a.textContent=s.title||url;a.style.display="block";list.append(a);}pending.append(list);}if(mode==="image"&&!image)pending.textContent+=" No image URL was returned by the backend.";if(mode==="video"&&!video)pending.textContent+=" No video URL was returned by the backend.";}
async function run(event){event?.preventDefault();const text=prompt.value.trim();if((!text&&!attachment)||busy)return;if(mode==="video"){bubble("Video generation is not connected yet. No fake result will be shown.","ai");return;}busy=true;submit.disabled=true;const sendText=text;bubble(text||"Attached image","user");const pending=bubble(mode==="image"?"Creating your image…":mode==="video"?"Preparing video request…":"Working…");prompt.value="";
try{
 const client=window.supabaseClient||window.cloudClient;
 if(!client?.auth)throw new Error("Supabase connection is not ready. Reload and sign in.");
 const {data:sessionData,error:sessionError}=await client.auth.getSession();
 const session=sessionData?.session;if(sessionError||!session)throw new Error("Please sign in to New Star first.");
 if(mode==="chat"&&attachment){
   const imageData=await toBase64(attachment);
   const result=await client.functions.invoke("ai-gateway",{body:{action:"vision",prompt:sendText||"Describe this image and explain its visible contents.",image:imageData,mimeType:attachment.type}});
   if(result.error||!result.data?.ok)throw new Error(result.data?.error||"Image analysis is unavailable.");
   pending.textContent=result.data.answer||"The image model returned an empty response.";
 }else if(mode==="research"){
   const result=await client.functions.invoke("ai-gateway",{body:{action:"research",query:sendText}});
   if(result.error||!result.data?.ok)throw new Error(result.data?.error||"Live research is unavailable.");
   pending.textContent=result.data.answer||"Research returned an empty answer.";
   const sources=Array.isArray(result.data.sources)?result.data.sources:[];
   if(sources.length){const heading=document.createElement("strong");heading.textContent="Sources";heading.style.display="block";heading.style.marginTop="12px";pending.append(heading);for(const source of sources){const url=mediaUrl(source.url);if(!url)continue;const link=document.createElement("a");link.href=url;link.target="_blank";link.rel="noopener noreferrer";link.textContent=source.title||url;link.style.display="block";link.style.marginTop="6px";pending.append(link);}}
 }else if(mode==="chat"){
   const result=await client.functions.invoke("ai-gateway",{body:{messages:[{role:"user",content:sendText}],conversationId:conversationId||undefined,language:"auto"}});
   if(result.error||!result.data?.ok)throw new Error(result.data?.error||"New Star AI chat service is unavailable.");
   pending.textContent=result.data.answer||"The AI returned an empty response.";if(result.data.memorySaved===false){const memoryNote=document.createElement("small");memoryNote.textContent="This reply was generated, but conversation saving is temporarily unavailable.";memoryNote.style.display="block";memoryNote.style.opacity=".75";pending.append(memoryNote);}
   conversationId=result.data.conversationId||conversationId;if(conversationId)localStorage.setItem("new-star-ai-conversation",conversationId);const voiceButton=document.createElement("button");voiceButton.type="button";voiceButton.textContent="▶ Listen";voiceButton.className="ns-listen";voiceButton.addEventListener("click",()=>speakText(result.data.answer||"",voiceButton));pending.append(voiceButton);
 }else if(mode==="image"){
   if(attachment)throw new Error("Image editing with uploads is not enabled in the current AI gateway yet. Text-to-image is available when a provider is configured.");
   const result=await client.functions.invoke("ai-gateway",{body:{action:"image",prompt:sendText,language:"auto",imageSize:$("ns-size").value}});
   if(result.error||!result.data?.ok)throw new Error(result.data?.error||"Image generation is unavailable. Check the server-side Gemini/OpenAI provider configuration.");
   if(typeof result.data.image!=="string"||result.data.image.length<100)throw new Error("The image provider returned no usable image data.");
   const mime=/^image\/(png|jpeg|webp)$/i.test(result.data.mimeType||"")?result.data.mimeType:"image/png";
   pending.textContent="Image created · "+(result.data.provider||"AI provider");
   const img=document.createElement("img");img.alt="AI-generated image";img.loading="lazy";img.src="data:"+mime+";base64,"+result.data.image;img.style.cssText="display:block;width:100%;max-width:640px;border-radius:12px;margin-top:10px";
   const save=document.createElement("a");save.href=img.src;save.download="new-star-ai-image."+({"image/jpeg":"jpg","image/webp":"webp"}[mime]||"png");save.textContent="Save image";save.style.display="inline-block";save.style.marginTop="10px";pending.append(img,save);
 }else if(mode==="research"){
   throw new Error("Live Research needs a connected search provider. The current Supabase AI gateway supports chat, image generation and voice, but does not yet provide web-search results.");
 }else{
   throw new Error("Video generation needs a video model server and job queue. This feature is not connected yet; no fake video will be shown.");
 }
 attachment=null;fileInput.value="";$("ns-attachment-wrap").hidden=true;
}catch(error){pending.textContent=error.message||"AI backend unavailable.";pending.dataset.error="true";if(!prompt.value.trim())prompt.value=sendText;}
finally{busy=false;submit.disabled=false;prompt.focus();}
}
$("ns-mic").addEventListener("click",toggleVoice);
$("ns-new-chat").addEventListener("click",()=>{if(busy)return;conversationId="";localStorage.removeItem("new-star-ai-conversation");output.replaceChildren();const empty=document.createElement("div");empty.className="ns-empty";empty.innerHTML='<div class="ns-orb">✦</div><b>New conversation</b><p>Start a fresh chat with New Star AI.</p>';output.append(empty);});
form.addEventListener("submit",run);prompt.addEventListener("keydown",e=>{if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();form.requestSubmit();}});
async function refreshEngineStatus(){
 const client=window.supabaseClient||window.cloudClient;
 const session=await getSession().catch(()=>null);
 if(!session){status.textContent="Sign in required";status.style.color="#f6c66b";return;}
 if(!client?.functions){status.textContent="Backend unavailable";status.style.color="#ff9b9b";return;}
 status.textContent="Checking AI providers…";
 try{
  const result=await client.functions.invoke("ai-gateway",{body:{action:"status"}});
  if(result.error||!result.data?.ok)throw new Error(result.data?.error||"Gateway status check failed");
  const health=result.data.health||{};
  const model=health.newStarHostedModel;
  const names={gemini:"Gemini",openai:"OpenAI",openrouter:"OpenRouter"};
  const descriptions={not_configured:"not configured",invalid_key:"invalid key",access_denied:"access denied",provider_rate_limited:"provider rate-limited",provider_unreachable:"unreachable",credits_exhausted:"credits exhausted",credits_available:"credits available",key_valid_inference_not_tested:"key valid · inference not tested",key_valid_credit_balance_unknown:"key valid · credits unknown",key_valid_credit_check_unreachable:"credit check unavailable"};
  const providerSummary=Object.entries(names).map(([key,label])=>{
   const item=health[key];
   if(!item?.configured)return label+": not configured";
   const detail=descriptions[item.status]||item.status||"check unavailable";
   return label+": "+detail;
  }).join(" · ");
  if(model?.reachable&&model?.modelAvailable){
   status.textContent="New Star model online · "+providerSummary;
   status.style.color="#91e6b0";
  }else{
   status.textContent=providerSummary;
   status.style.color=Object.values(health).some(item=>item?.status==="invalid_key"||item?.status==="credits_exhausted")?"#ff9b9b":"#f6c66b";
  }
  foot.textContent="Provider checks do not generate content or charge inference. Gemini/OpenAI quota is not tested.";
 }catch(error){status.textContent="AI service unavailable";status.style.color="#ff9b9b";foot.textContent=error.message||"Could not check New Star AI service status.";}
}
window.addEventListener("load",()=>{refreshEngineStatus();loadApiKeys();});
async function apiKeyRequest(body){
 const client=window.supabaseClient||window.cloudClient;
 if(!client?.functions)throw new Error("New Star connection is not ready.");
 const {data:sessionData}=await client.auth.getSession();
 if(!sessionData?.session)throw new Error("Sign in to New Star to manage API keys.");
 const result=await client.functions.invoke("ai-gateway",{body});
 if(result.error||!result.data?.ok)throw new Error(result.data?.error||"API key request failed.");
 return result.data;
}
function apiKeyMessage(text,isError=false){
 const box=$("ns-api-new");box.hidden=false;box.replaceChildren();
 const p=document.createElement("p");p.textContent=text;p.style.color=isError?"#ffaaaa":"#a8e7bf";box.append(p);
}
async function loadApiKeys(){
 const list=$("ns-api-list");list.textContent="Loading API keys…";
 try{
  const data=await apiKeyRequest({action:"api_key_list"});
  list.replaceChildren();
  if(!data.keys.length){const p=document.createElement("p");p.textContent="No API keys created yet.";list.append(p);return;}
  for(const key of data.keys){
   const card=document.createElement("div");card.className="ns-api-key-item";
   const info=document.createElement("div");
   const name=document.createElement("b");name.textContent=key.name+" · "+key.key_prefix+"…";
   const meta=document.createElement("small");meta.textContent=(key.scopes||[]).join(", ")+" · "+key.rate_limit+"/min · "+(key.revoked_at?"Revoked":key.expires_at?"Expires "+new Date(key.expires_at).toLocaleDateString():"No expiry");
   info.append(name,meta);card.append(info);
   if(!key.revoked_at){const revoke=document.createElement("button");revoke.type="button";revoke.textContent="Revoke";revoke.addEventListener("click",async()=>{
    if(!confirm("Revoke this API key? Any app using it will stop working."))return;
    revoke.disabled=true;try{await apiKeyRequest({action:"api_key_revoke",keyId:key.id});apiKeyMessage("API key revoked.");await loadApiKeys();}catch(error){apiKeyMessage(error.message,true);revoke.disabled=false;}
   });card.append(revoke);}
   list.append(card);
  }
 }catch(error){list.textContent=error.message||"Could not load API keys.";}
}
$("ns-api-form").addEventListener("submit",async event=>{
 event.preventDefault();const button=$("ns-api-create");button.disabled=true;
 try{
  const scopes=Array.from($("ns-api-scopes").selectedOptions).map(option=>option.value);
  if(!scopes.length)throw new Error("Choose at least one permission.");
  const expiry=$("ns-api-expiry").value;
  const result=await apiKeyRequest({action:"api_key_create",name:$("ns-api-name").value,scopes,rateLimit:Number($("ns-api-limit").value),expiresAt:expiry?new Date(expiry+"T23:59:59").toISOString():null});
  const box=$("ns-api-new");box.hidden=false;box.replaceChildren();
  const title=document.createElement("b");title.textContent="Copy your new API key now — it will not be shown again.";
  const value=document.createElement("code");value.className="ns-api-secret";value.textContent=result.key;
  const copy=document.createElement("button");copy.type="button";copy.textContent="Copy key";copy.addEventListener("click",async()=>{try{await navigator.clipboard.writeText(result.key);copy.textContent="Copied";}catch{apiKeyMessage("Clipboard unavailable. Select and copy the key manually.",true);}});
  box.append(title,value,copy);$("ns-api-name").value="";await loadApiKeys();
 }catch(error){apiKeyMessage(error.message||"Could not create API key.",true);}
 finally{button.disabled=false;}
});
$("ns-api-refresh").addEventListener("click",loadApiKeys);

setMode("chat");
})();