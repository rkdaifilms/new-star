(()=>{"use strict";
const $=id=>document.getElementById(id);
const form=$("ns-form"),prompt=$("ns-prompt"),output=$("ns-output"),submit=$("ns-submit"),status=$("engine-status"),foot=$("ns-footnote"),fileInput=$("ns-file");
async function gatewayErrorMessage(result,fallback){const direct=result?.data?.error;if(typeof direct==="string"&&direct.trim())return direct;try{const response=result?.error?.context;if(response&&typeof response.clone==="function"){const payload=await response.clone().json();if(typeof payload?.error==="string"&&payload.error.trim())return payload.error;}}catch(_){}return fallback;}
function selectedAppLanguage(){try{const p=window.NewStarPreferences?.get?.()||JSON.parse(localStorage.getItem("newstar_preferences")||"{}");return typeof p.language==="string"&&/^[a-z]{2}$/i.test(p.language)?p.language:"";}catch(_){return "";}}
function automaticLanguageOptions(){return {language:"auto"};}function setPrivateImageSizeMode(privateOnly){const select=$("ns-size");if(!select)return;for(const option of select.options){const supported=!privateOnly||option.value==="1K";option.disabled=!supported;option.hidden=!supported;}if(privateOnly)select.value="1K";}
function requestLanguage(){return lastInputWasVoice?(lastTranscriptionLanguage||automaticLanguageOptions().language):(selectedAppLanguage()||automaticLanguageOptions().language);}
let lastTranscriptionLanguage="",lastInputWasVoice=false;
let mode="chat",busy=false,attachment=null,conversationId=localStorage.getItem("new-star-ai-conversation")||"",recorder=null,recordedChunks=[];
const modes={chat:["AI Chat","Ask questions, plan and create."],research:["Live Research","Search the web and return source links."],image:["Image Generation","Describe an image and choose output size."],video:["Video Generation","Describe a short video and choose its format and duration."]};
function setResearchMode(privateOnly){
 modes.research=privateOnly
  ?["Private Search","Search pages indexed in New Star. Paste a public website URL to add it to your private index."]
  :["Live Research","Search the web and return source links."];
 if(mode==="research"){$("mode-title").textContent=modes.research[0];$("mode-help").textContent=modes.research[1];}
}
function bubble(text,kind="ai"){const el=document.createElement("div");el.className="ns-message "+(kind==="user"?"ns-user":"ns-ai");el.textContent=text;output.append(el);output.scrollTop=output.scrollHeight;return el;}
function toBase64(blob){return new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(",")[1]||"");reader.onerror=()=>reject(new Error("Could not read voice recording."));reader.readAsDataURL(blob);});}
let recordingLimitTimer=null,voiceStream=null,voiceSendPending=false;
function resetMicButton(){
 const mic=$("ns-mic");
 mic.disabled=false;
 mic.textContent="🎙 Voice";
 mic.setAttribute("aria-label","Start voice recording");
 mic.classList.remove("ns-recording");
}
function stopVoiceTracks(){
 if(voiceStream){voiceStream.getTracks().forEach(track=>track.stop());voiceStream=null;}
}
async function transcribeVoice(blob){
 const client=window.supabaseClient||window.cloudClient;
 if(!client?.functions)throw new Error("New Star connection is not ready. Reload the page and sign in again.");
 const mime=(blob.type||"audio/webm").split(";")[0].toLowerCase();
 const supported=["audio/webm","audio/ogg","audio/mp4","audio/mpeg","audio/mp3","audio/wav","audio/m4a"];
 if(!supported.includes(mime))throw new Error("This phone recorded an unsupported audio format: "+mime);
 const audio=await toBase64(blob);
 const result=await client.functions.invoke("ai-gateway",{body:{action:"transcribe",audio,mimeType:mime}});
 if(result.error||!result.data?.ok)throw new Error(await gatewayErrorMessage(result,"New Star could not transcribe this recording."));
 const transcript=String(result.data.transcript||"").trim();
 if(!transcript)throw new Error("No words were detected. Record again and speak clearly.");
 lastTranscriptionLanguage=result.data.language&&result.data.language!=="und"?result.data.language:"";
 lastInputWasVoice=true;
 prompt.value=(prompt.value.trim()?prompt.value.trim()+" ":"")+transcript;
 prompt.dispatchEvent(new Event("change",{bubbles:true}));
 prompt.focus();
 prompt.setSelectionRange(prompt.value.length,prompt.value.length);
}
async function toggleVoice(){
 const mic=$("ns-mic");
 if(recorder&&recorder.state==="recording"){
  mic.disabled=true;
  mic.textContent="Finishing recording…";
  if(recordingLimitTimer){clearTimeout(recordingLimitTimer);recordingLimitTimer=null;}
  try{recorder.stop();}catch(error){stopVoiceTracks();recorder=null;resetMicButton();bubble(error.message||"Could not stop the recording.","ai");}
  return;
 }
 if(!navigator.mediaDevices||typeof navigator.mediaDevices.getUserMedia!=="function"||typeof window.MediaRecorder!=="function"){
  bubble("This browser cannot record audio here. Open this page in updated Chrome and allow microphone access.","ai");
  return;
 }
 try{
  mic.disabled=true;
  mic.textContent="Allow microphone…";
  voiceStream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});
  const mimeCandidates=["audio/webm;codecs=opus","audio/webm","audio/ogg;codecs=opus","audio/ogg","audio/mp4"];
  const mimeType=mimeCandidates.find(type=>MediaRecorder.isTypeSupported(type));
  recorder=mimeType?new MediaRecorder(voiceStream,{mimeType}):new MediaRecorder(voiceStream);
  recordedChunks=[];
  recorder.ondataavailable=event=>{if(event.data&&event.data.size>0)recordedChunks.push(event.data);};
  recorder.onerror=event=>{
    voiceSendPending=false;submit.disabled=false;
   stopVoiceTracks();
   if(recordingLimitTimer){clearTimeout(recordingLimitTimer);recordingLimitTimer=null;}
   recorder=null;
   resetMicButton();
   bubble(event.error?.message||"Audio recording failed. Check microphone permission and try again.","ai");
  };
  recorder.onstop=async()=>{
   const finishedRecorder=recorder;
   const blob=new Blob(recordedChunks,{type:finishedRecorder?.mimeType||mimeType||"audio/webm"});
   stopVoiceTracks();
   if(recordingLimitTimer){clearTimeout(recordingLimitTimer);recordingLimitTimer=null;}
   recorder=null;
   recordedChunks=[];
   resetMicButton();
   if(blob.size<100){voiceSendPending=false;submit.disabled=false;mic.disabled=false;resetMicButton();bubble("Recording is empty or too short. Tap the microphone and try again.","ai");return;}
   mic.disabled=true;
   mic.textContent="Transcribing…";
   try{
    await transcribeVoice(blob);
    if(voiceSendPending){
     voiceSendPending=false;
     submit.disabled=false;
     bubble("Voice message transcribed. Sending to New Star AI…","ai");
     form.requestSubmit();
    }else{
     bubble("Voice converted to text. Review it, then tap Send ↗.","ai");
    }
   }catch(error){
    voiceSendPending=false;
    submit.disabled=false;
    bubble(error.message||"Voice transcription failed. Your recording was not sent.","ai");
   }finally{mic.disabled=false;mic.textContent="🎙 Voice";}
  };
  recorder.start(250);
  mic.disabled=false;
  mic.textContent="■ Stop recording";
  mic.setAttribute("aria-label","Stop voice recording");
  mic.classList.add("ns-recording");
  recordingLimitTimer=setTimeout(()=>{
   if(recorder&&recorder.state==="recording"){
    bubble("Recording reached the 90-second limit. Finishing it now…","ai");
    try{recorder.stop();}catch(_){stopVoiceTracks();recorder=null;resetMicButton();}
   }
  },90000);
 }catch(error){
  stopVoiceTracks();
  recorder=null;
  resetMicButton();
  const message=error.name==="NotAllowedError"?"Microphone permission is blocked. Allow microphone access for this site in Chrome settings.":error.name==="NotFoundError"?"No microphone was found on this device.":error.message||"Could not start microphone recording.";
  bubble(message,"ai");
 }
}
function pcmToWav(pcm,sampleRate){const header=new ArrayBuffer(44);const view=new DataView(header);const write=(offset,value)=>{for(let i=0;i<value.length;i++)view.setUint8(offset+i,value.charCodeAt(i));};write(0,"RIFF");view.setUint32(4,36+pcm.byteLength,true);write(8,"WAVE");write(12,"fmt ");view.setUint32(16,16,true);view.setUint16(20,1,true);view.setUint16(22,1,true);view.setUint32(24,sampleRate,true);view.setUint32(28,sampleRate*2,true);view.setUint16(32,2,true);view.setUint16(34,16,true);write(36,"data");view.setUint32(40,pcm.byteLength,true);return new Blob([header,pcm],{type:"audio/wav"});}
async function speakText(text,button,language=requestLanguage()){const client=window.supabaseClient||window.cloudClient;if(!client?.functions){bubble("New Star AI connection is not ready.","ai");return;}button.disabled=true;const old=button.textContent;let audioUrl="";let audioElement=null;const cleanup=()=>{if(audioUrl){URL.revokeObjectURL(audioUrl);audioUrl="";}button.textContent=old;button.disabled=false;};button.textContent="Preparing voice…";try{const result=await client.functions.invoke("ai-gateway",{body:{action:"speak",text,language}});if(result.error||!result.data?.ok)throw new Error(await gatewayErrorMessage(result,"AI voice is unavailable."));if(typeof result.data.audio!=="string"||!result.data.audio)throw new Error("AI voice returned no audio data.");const binary=atob(result.data.audio);const bytes=new Uint8Array(binary.length);for(let i=0;i<binary.length;i++)bytes[i]=binary.charCodeAt(i);const mime=String(result.data.mimeType||"audio/mpeg");const rate=Number(mime.match(/rate=(\d+)/i)?.[1]||24000);const blob=/audio\/(?:l16|pcm)/i.test(mime)?pcmToWav(bytes,rate):new Blob([bytes],{type:mime});audioUrl=URL.createObjectURL(blob);audioElement=new Audio(audioUrl);button.textContent="Playing…";audioElement.onended=cleanup;audioElement.onerror=cleanup;await audioElement.play();}catch(error){cleanup();bubble(error.message||"Voice playback failed.","ai");}}
function setMode(next){if(next!=="chat"){lastInputWasVoice=false;lastTranscriptionLanguage="";}mode=next;document.querySelectorAll("[data-mode]").forEach(b=>b.classList.toggle("active",b.dataset.mode===mode));$("mode-title").textContent=modes[mode][0];$("mode-help").textContent=modes[mode][1];prompt.placeholder=mode==="image"?"Describe the image you want to generate…":mode==="video"?"Describe the video you want to create…":"Ask anything…";$("ns-size").hidden=mode!=="image";$("ns-video-controls").hidden=mode!=="video";$("ns-upload").hidden=!(mode==="chat"||mode==="image");$("ns-mic").hidden=mode!=="chat";submit.textContent=mode==="image"?"Generate image ↗":mode==="video"?"Generate video ↗":"Send ↗";}
document.querySelectorAll("[data-mode]").forEach(b=>b.addEventListener("click",()=>{if(!busy)setMode(b.dataset.mode);}));
fileInput.addEventListener("change",()=>{attachment=fileInput.files?.[0]||null;$("ns-attachment-wrap").hidden=!attachment;$("ns-attachment-name").textContent=attachment?attachment.name:"";if(attachment&&attachment.size>6*1024*1024){attachment=null;fileInput.value="";$("ns-attachment-wrap").hidden=true;bubble("Image must be smaller than 6 MB.","ai");}});
$("ns-clear-file").addEventListener("click",()=>{attachment=null;fileInput.value="";$("ns-attachment-wrap").hidden=true;});
async function getSession(){const client=window.supabaseClient||window.cloudClient||window.supabase;if(!client?.auth)return null;const{data}=await client.auth.getSession();return data?.session||null;}
function detectMediaMode(text){
 const value=String(text||"").trim();
 const imageIntent=/(?:generate|create|make|draw|paint|render|edit)\b.{0,60}\b(image|picture|photo|illustration|thumbnail)\b|\b(image|picture|photo|illustration|thumbnail)\b.{0,60}\b(generate|create|make|draw|paint|render|edit)\b/i.test(value)||/(?:तस्वीर|फोटो|चित्र|इमेज|छवि).{0,60}(?:बना|तैयार|जनरेट|बदल|एडिट)|(?:बना|तैयार|जनरेट|बदल|एडिट).{0,60}(?:तस्वीर|फोटो|चित्र|इमेज|छवि)/u.test(value);
 const videoIntent=/(?:generate|create|make|produce|render)\b.{0,60}\b(video|clip|animation)\b|\b(video|clip|animation)\b.{0,60}\b(generate|create|make|produce|render)\b/i.test(value)||/(?:वीडियो|क्लिप|एनीमेशन).{0,60}(?:बना|तैयार|जनरेट)|(?:बना|तैयार|जनरेट).{0,60}(?:वीडियो|क्लिप|एनीमेशन)/u.test(value);
 if(videoIntent)return "video";
 if(imageIntent)return "image";
 return "chat";
}
function mediaUrl(value){try{const u=new URL(value,location.href);return ["https:","http:"].includes(u.protocol)?u.href:null;}catch{return null;}}
function renderResult(data,pending){pending.textContent=data.answer||data.message||"Completed.";const image=mediaUrl(data.imageUrl||data.image_url);if(image){const img=document.createElement("img");img.alt="AI-generated image";img.loading="lazy";img.src=image;pending.append(img);}const video=mediaUrl(data.videoUrl||data.video_url);if(video){const v=document.createElement("video");v.controls=true;v.playsInline=true;v.src=video;v.style.cssText="width:100%;max-width:640px;border-radius:12px;margin-top:10px";pending.append(v);}if(Array.isArray(data.sources)&&data.sources.length){const list=document.createElement("div");for(const s of data.sources){const url=mediaUrl(s?.url);if(!url)continue;const a=document.createElement("a");a.href=url;a.target="_blank";a.rel="noopener noreferrer";a.textContent=s.title||url;a.style.display="block";list.append(a);}pending.append(list);}if(mode==="image"&&!image)pending.textContent+=" No image URL was returned by the backend.";if(mode==="video"&&!video)pending.textContent+=" No video URL was returned by the backend.";}
async function run(event){event?.preventDefault();if(recorder&&recorder.state==="recording"){voiceSendPending=true;submit.disabled=true;const mic=$("ns-mic");mic.textContent="Transcribing & sending…";if(recordingLimitTimer){clearTimeout(recordingLimitTimer);recordingLimitTimer=null;}try{recorder.stop();}catch(error){voiceSendPending=false;submit.disabled=false;resetMicButton();bubble(error.message||"Could not stop the recording.","ai");}return;}const text=prompt.value.trim();if((!text&&!attachment)||busy)return;if(text&&!lastInputWasVoice)lastTranscriptionLanguage="";const sendText=text;const responseLanguage=requestLanguage();if(mode==="chat"&&sendText){const detectedMode=detectMediaMode(sendText);if(detectedMode!=="chat")setMode(detectedMode);}busy=true;submit.disabled=true;bubble(text||"Attached image","user");const pending=bubble(mode==="image"?"Creating your image…":mode==="video"?"Preparing video request…":"Working…");prompt.value="";
try{
 const client=window.supabaseClient||window.cloudClient;
 if(!client?.auth)throw new Error("Supabase connection is not ready. Reload and sign in.");
 const {data:sessionData,error:sessionError}=await client.auth.getSession();
 const session=sessionData?.session;if(sessionError||!session)throw new Error("Please sign in to New Star first.");
 if(mode==="chat"&&attachment){
   const imageData=await toBase64(attachment);
   const result=await client.functions.invoke("ai-gateway",{body:{action:"vision",prompt:sendText||"Describe this image and explain its visible contents.",image:imageData,mimeType:attachment.type}});
   if(result.error||!result.data?.ok)throw new Error(await gatewayErrorMessage(result,"Image analysis is unavailable."));
   pending.textContent=result.data.answer||"The image model returned an empty response.";
 }else if(mode==="research"){
   const crawlUrl=/^https?:\/\/[^\s]+$/i.test(sendText.trim());
   const result=await client.functions.invoke("ai-gateway",{body:crawlUrl?{action:"crawl",url:sendText.trim()}:{action:"search",query:sendText,language:responseLanguage,limit:8}});
   if(result.error||!result.data?.ok)throw new Error(await gatewayErrorMessage(result,crawlUrl?"Private website indexing is unavailable.":"Live research is unavailable."));
   const sources=crawlUrl?[]:(Array.isArray(result.data.results)?result.data.results:[]);
   if(crawlUrl){
     pending.textContent="Page indexed in the New Star private search library: "+(result.data.title||result.data.url)+" · "+(result.data.pagesIndexed||1)+" page(s), "+(result.data.charactersIndexed||0)+" characters indexed. You can now search this site.";
   }else{
     pending.textContent=typeof result.data.answer==="string"&&result.data.answer.trim()?result.data.answer:(result.data.count?"Search found "+result.data.count+" source(s).":"No matching pages were found in the private crawl index yet. Paste a public page URL to index it first.");const countNote=document.createElement("small");countNote.textContent=(result.data.count||0)+" verified source link(s) · "+(result.data.provider||"Search");countNote.style.display="block";countNote.style.marginTop="10px";countNote.style.opacity=".75";pending.append(countNote);
   }
   if(sources.length){const heading=document.createElement("strong");heading.textContent="Sources";heading.style.display="block";heading.style.marginTop="12px";pending.append(heading);for(const source of sources){const url=mediaUrl(source.url);if(!url)continue;const link=document.createElement("a");link.href=url;link.target="_blank";link.rel="noopener noreferrer";link.textContent=source.title||url;link.style.display="block";link.style.marginTop="6px";pending.append(link);}}
 }else if(mode==="chat"){
   const chatMessages=[{role:"user",content:sendText}];
   let result=await client.functions.invoke("ai-gateway",{body:{messages:chatMessages,conversationId:conversationId||undefined,language:responseLanguage}});
   if((result.error||!result.data?.ok)&&conversationId){
     const detail=await gatewayErrorMessage(result,"");
     if(/conversation (?:id is invalid|not found)|could not load this conversation/i.test(detail)){
       conversationId="";
       localStorage.removeItem("new-star-ai-conversation");
       result=await client.functions.invoke("ai-gateway",{body:{messages:chatMessages,language:responseLanguage}});
     }
   }
   if(result.error||!result.data?.ok)throw new Error(await gatewayErrorMessage(result,"New Star AI chat service is unavailable."));
   pending.textContent=result.data.answer||"The AI returned an empty response.";if(result.data.memorySaved===false){const memoryNote=document.createElement("small");memoryNote.textContent="This reply was generated, but conversation saving is temporarily unavailable.";memoryNote.style.display="block";memoryNote.style.opacity=".75";pending.append(memoryNote);}
   lastInputWasVoice=false;lastTranscriptionLanguage="";conversationId=result.data.conversationId||conversationId;if(conversationId)localStorage.setItem("new-star-ai-conversation",conversationId);const voiceButton=document.createElement("button");voiceButton.type="button";voiceButton.textContent="▶ Listen";voiceButton.className="ns-listen";voiceButton.addEventListener("click",()=>speakText(result.data.answer||"",voiceButton,responseLanguage));pending.append(voiceButton);
 }else if(mode==="image"){
   const imagePayload=attachment?await toBase64(attachment):"";
   const result=await client.functions.invoke("ai-gateway",{body:{action:"image",prompt:sendText,language:selectedAppLanguage()||"auto",imageSize:$("ns-size").value,image:imagePayload||undefined,mimeType:attachment?.type||undefined}});
   if(result.error||!result.data?.ok)throw new Error(await gatewayErrorMessage(result,"Image generation is unavailable. Check the server-side provider configuration."));
   if(typeof result.data.image!=="string"||result.data.image.length<100)throw new Error("The image provider returned no usable image data.");
   const mime=/^image\/(png|jpeg|webp)$/i.test(result.data.mimeType||"")?result.data.mimeType:"image/png";
   pending.textContent="Image created · "+(result.data.provider||"AI provider");
   const img=document.createElement("img");img.alt="AI-generated image";img.loading="lazy";img.src="data:"+mime+";base64,"+result.data.image;img.style.cssText="display:block;width:100%;max-width:640px;border-radius:12px;margin-top:10px";
   const save=document.createElement("a");save.href=img.src;save.download="new-star-ai-image."+({"image/jpeg":"jpg","image/webp":"webp"}[mime]||"png");save.textContent="Save image";save.style.display="inline-block";save.style.marginTop="10px";pending.append(img,save);
 }else if(mode==="video"){
   const started=await client.functions.invoke("ai-gateway",{body:{action:"video",prompt:sendText,aspectRatio:$("ns-video-aspect").value,resolution:$("ns-video-resolution").value,duration:$("ns-video-duration").value}});
   if(started.error||!started.data?.ok||!started.data?.jobId)throw new Error(started.data?.error||"The video provider could not start a generation job.");
   const jobId=started.data.jobId;const deadline=Date.now()+600000;let completed=null;
   pending.textContent="Video job accepted by "+(started.data.provider||"the configured provider")+". Rendering…";
   while(Date.now()<deadline){
     await new Promise(resolve=>setTimeout(resolve,5000));
     const check=await client.functions.invoke("ai-gateway",{body:{action:"video-status",jobId}});
     if(check.error)throw new Error(check.data?.error||"Could not check video generation progress.");
     const state=check.data;
     if(!state?.ok)throw new Error(state?.error||"Video generation could not be completed.");
     if(state.status==="failed")throw new Error(state.error||"Video generation failed.");
     if(state.status==="completed"){completed=state;break;}
     pending.textContent="Video is rendering on "+(started.data.provider==="new_star_private"?"New Star private GPU":"the configured video provider")+". Please keep this page open…";
   }
   if(!completed)throw new Error("Video generation is taking longer than expected. The server job may still be running; check again later.");
   const videoUrl=mediaUrl(completed.videoUrl);if(!videoUrl)throw new Error("The video finished, but no valid playback URL was returned.");
   pending.textContent="Video created · "+(completed.model||started.data.model||"Google Cloud Vertex AI");
   const video=document.createElement("video");video.controls=true;video.playsInline=true;video.preload="metadata";video.src=videoUrl;video.style.cssText="display:block;width:100%;max-width:640px;border-radius:12px;margin-top:10px";
   const save=document.createElement("a");save.href=videoUrl;save.download="new-star-ai-video.mp4";save.textContent="Save video";save.style.display="inline-block";save.style.marginTop="10px";pending.append(video,save);
 }
 attachment=null;fileInput.value="";$("ns-attachment-wrap").hidden=true;
}catch(error){pending.textContent=error.message||"AI backend unavailable.";pending.dataset.error="true";if(sendText&&!prompt.value.trim())prompt.value=sendText;}
finally{busy=false;submit.disabled=false;prompt.focus();}
}
$("ns-mic").addEventListener("click",toggleVoice);
$("ns-new-chat").addEventListener("click",()=>{if(busy)return;conversationId="";localStorage.removeItem("new-star-ai-conversation");output.replaceChildren();const empty=document.createElement("div");empty.className="ns-empty";empty.innerHTML='<div class="ns-orb">✦</div><b>New conversation</b><p>Start a fresh chat with New Star AI.</p>';output.append(empty);});
form.addEventListener("submit",run);prompt.addEventListener("input",()=>{lastInputWasVoice=false;lastTranscriptionLanguage="";});prompt.addEventListener("keydown",e=>{if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();form.requestSubmit();}});
async function refreshEngineStatus(){
 const client=window.supabaseClient||window.cloudClient;
 const session=await getSession().catch(()=>null);
 if(!session){status.textContent="Sign in required";status.style.color="#f6c66b";const note=$("ns-auth-note");if(note)note.hidden=false;return;}
 const note=$("ns-auth-note");if(note)note.hidden=true;
 if(!client?.functions){status.textContent="Backend unavailable";status.style.color="#ff9b9b";return;}
 status.textContent="Checking AI providers…";
 try{
  const result=await client.functions.invoke("ai-gateway",{body:{action:"status"}});
  if(result.error||!result.data?.ok)throw new Error(await gatewayErrorMessage(result,"Gateway status check failed"));
  const health=result.data.health||{};
  const providers=result.data.providers||{};
  const capabilities=result.data.capabilities||{};
  const model=health.newStarHostedModel||{};
  const providerHealth=health.providers||{};
  if(result.data.mode==="self_hosted"){
   setResearchMode(true);
   setPrivateImageSizeMode(true);
   const privateOnline=Boolean(model.reachable&&model.modelAvailable);
   status.textContent=privateOnline?"New Star private model: online · External AI providers: disabled":model.configured?"New Star private model: configured, not verified · External AI providers: disabled":"New Star private model: not configured · External AI providers: disabled";
   status.style.color=privateOnline?"#91e6b0":"#f6c66b";
   const capabilitySummary=["Chat "+(capabilities.chat?.configured?"ready":"not configured"),"Images "+(capabilities.imageGeneration?.configured?"configured":"not configured"),"Vision "+(capabilities.imageUnderstanding?.configured?"configured":"not configured"),"Speech "+(capabilities.speechGeneration?.configured?"configured":"not configured"),"Video "+(capabilities.videoGeneration?.configured?"configured":"not configured"),"Search "+(capabilities.webSearch?.supported?"available":"private worker not deployed")].join(" · ");
   foot.textContent=capabilitySummary+". Self-hosted-only mode: no Gemini, OpenAI or OpenRouter inference fallback.";
   return;
  }
  setResearchMode(false);
  setPrivateImageSizeMode(false);
  const providerState=(name,configured)=>{if(!configured)return "not configured";const item=providerHealth[name]||{};if(item.quotaAvailable===false)return "credits exhausted";if(item.status==="credits_available")return "online · credits available";if(item.authorized)return "online";if(item.reachable)return "reachable, access denied";return "unreachable";};
  const providerSummary=["Gemini: "+providerState("gemini",providers.gemini),"OpenAI: "+providerState("openai",providers.openai),"OpenRouter: "+providerState("openrouter",providers.openrouter)].join(" · ");
  const modelSummary=model.configured?(model.reachable&&model.modelAvailable?"New Star hosted model: online":"New Star hosted model: configured, not verified"):"New Star hosted model: not configured";
  status.textContent=modelSummary+" · "+providerSummary;
  status.style.color=(model.reachable&&model.modelAvailable)||providerHealth.gemini?.authorized||providerHealth.openai?.authorized||(providerHealth.openrouter?.authorized&&providerHealth.openrouter?.quotaAvailable!==false)?"#91e6b0":"#f6c66b";
  const capabilitySummary=["Chat "+(capabilities.chat?.configured?"ready":"not configured"),"Images "+(capabilities.imageGeneration?.configured?"configured":"not configured"),"Image understanding "+(capabilities.imageUnderstanding?.configured?"configured":"not configured"),"Voice "+(capabilities.speechGeneration?.configured?"configured":"not configured"),"Video "+(capabilities.videoGeneration?.configured?"service account configured":"not configured"),"Search "+(capabilities.webSearch?.configured?"configured":"not configured")].join(" · ");
  foot.textContent=capabilitySummary+". Configuration checks do not prove provider billing, quota or successful generation.";
 }catch(error){status.textContent="AI service unavailable";status.style.color="#ff9b9b";foot.textContent=error.message||"Could not check New Star AI service status.";}
}
window.addEventListener("load",()=>{refreshEngineStatus();});


setMode("chat");
})();