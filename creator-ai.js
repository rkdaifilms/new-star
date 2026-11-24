(()=>{"use strict";
const $=id=>document.getElementById(id);
const db=()=>window.supabaseClient||window.cloudClient;
const SUPPORTED_LANGUAGES={hi:"Hindi",ne:"Nepali",en:"English",zh:"Chinese (Simplified)",es:"Spanish",fr:"French",ar:"Arabic",ja:"Japanese",de:"German",ru:"Russian",pt:"Portuguese",it:"Italian",ko:"Korean",tr:"Turkish",bn:"Bengali",ur:"Urdu",id:"Indonesian",vi:"Vietnamese",th:"Thai",fa:"Persian",sw:"Swahili",nl:"Dutch",pl:"Polish",uk:"Ukrainian",el:"Greek"};
function selectedAppLanguage(){try{const p=JSON.parse(localStorage.getItem("newstar_preferences")||"{}");return Object.prototype.hasOwnProperty.call(SUPPORTED_LANGUAGES,p.language)?p.language:"hi";}catch(_){return "hi";}}
const uiText=(key,fallback)=>window.NewStarTranslations?.[selectedAppLanguage()]?.[key]||fallback;

const log=$("chat-log"),prompt=$("prompt"),send=$("send"),voice=$("voice"),stopVoice=$("stop-voice"),status=$("gateway-status"),voiceStatus=$("voice-status"),notice=$("notice"),imageResult=$("image-result"),videoResult=$("video-result");
let history=[],busy=false,recorder=null,stream=null,chunks=[],recording=false,pendingSendAfterTranscription=false,lastLanguage="en",imageMode=false,videoMode=false,browserRecognition=null,browserTranscript="",browserInterimTranscript="",voiceDraftPrefix="";
const langMap={"ह":"hi","अ":"hi","क":"hi","ख":"hi","ग":"hi","घ":"hi","च":"hi","ज":"hi","ट":"hi","ड":"hi","त":"hi","द":"hi","न":"hi","प":"hi","ब":"hi","म":"hi","य":"hi","र":"hi","ल":"hi","व":"hi","श":"hi","स":"hi","क्ष":"hi","ज्ञ":"hi","ँ":"hi","ं":"hi","ः":"hi","ि":"hi","ा":"hi","े":"hi","ो":"hi","्":"hi","़":"hi","়":"bn","অ":"bn","আ":"bn","ই":"bn","উ":"bn","এ":"bn","ও":"bn","ক":"bn","খ":"bn","গ":"bn","চ":"bn","জ":"bn","ট":"bn","ড":"bn","ত":"bn","দ":"bn","ন":"bn","প":"bn","ব":"bn","ম":"bn","য":"bn","র":"bn","ল":"bn","শ":"bn","স":"bn","ং":"bn","ঃ":"bn","ા":"gu","ક":"gu","મ":"gu","ત":"gu","પ":"gu","ર":"gu","ન":"gu","અ":"gu","આ":"gu","ಕ":"kn","ತ":"kn","ನ":"kn","ಮ":"kn","ಪ":"kn","ಅ":"kn","ആ":"ml","ക":"ml","മ":"ml","ന":"ml","പ":"ml","த":"ta","ம":"ta","ந":"ta","ப":"ta","அ":"ta","త":"te","మ":"te","న":"te","క":"te","అ":"te","ಅ":"kn","ಅ":"kn","ಕ":"kn","ಮ":"kn","ತ":"kn","ಪ":"kn","ಯ":"kn","ಖ":"kn","ก":"th","ข":"th","ค":"th","ง":"th","จ":"th","ส":"th","ร":"th","ม":"th","ย":"th","ว":"th","ه":"ar","ا":"ar","ل":"ar","م":"ar","ن":"ar","ي":"ar","ع":"ar","ك":"ar","و":"ar","پ":"fa","چ":"fa","ژ":"fa","گ":"fa","ی":"fa","ا":"fa","ب":"fa","ت":"fa","ث":"fa","ج":"fa","ح":"fa","خ":"fa","د":"fa","ر":"fa","ز":"fa","س":"fa","ش":"fa","ص":"fa","ض":"fa","ط":"fa","ظ":"fa","ع":"fa","غ":"fa","ف":"fa","ق":"fa","ک":"fa","ل":"fa","م":"fa","ن":"fa","و":"fa","ھ":"ur","ے":"ur","ٹ":"ur","ڈ":"ur","ڑ":"ur","ں":"ur","گ":"ur","چ":"ur","پ":"ur","ک":"ur","ا":"ur","ب":"ur","ت":"ur","ث":"ur","ج":"ur","ح":"ur","خ":"ur","د":"ur","ر":"ur","ز":"ur","س":"ur","ش":"ur","ص":"ur","ض":"ur","ط":"ur","ظ":"ur","ع":"ur","غ":"ur","ف":"ur","ق":"ur","ل":"ur","م":"ur","ن":"ur","و":"ur","ي":"ur","ی":"ur","ئ":"ur","ء":"ur","ؤ":"ur","إ":"ar","أ":"ar","ة":"ar","ى":"ar","я":"ru","ы":"ru","э":"ru","ё":"ru","ж":"ru","щ":"ru","ф":"ru","ц":"ru","й":"ru","ш":"ru","ю":"ru","д":"ru","г":"ru","п":"ru","л":"ru","о":"ru","р":"ru","а":"ru","в":"ru","к":"ru","м":"ru","с":"ru","т":"ru","н":"ru","е":"ru","и":"ru","у":"ru","б":"ru","з":"ru","х":"ru","ч":"ru","ъ":"ru","ь":"ru","ў":"uk","і":"uk","є":"uk","ґ":"uk","ї":"uk","あ":"ja","い":"ja","う":"ja","え":"ja","お":"ja","か":"ja","き":"ja","く":"ja","け":"ja","こ":"ja","さ":"ja","し":"ja","す":"ja","せ":"ja","そ":"ja","た":"ja","ち":"ja","つ":"ja","て":"ja","と":"ja","な":"ja","に":"ja","ぬ":"ja","ね":"ja","の":"ja","は":"ja","ひ":"ja","ふ":"ja","へ":"ja","ほ":"ja","ア":"ja","イ":"ja","ウ":"ja","エ":"ja","オ":"ja","カ":"ja","キ":"ja","ク":"ja","ケ":"ja","コ":"ja","サ":"ja","シ":"ja","ス":"ja","セ":"ja","ソ":"ja","タ":"ja","チ":"ja","ツ":"ja","テ":"ja","ト":"ja","ニ":"ja","ヌ":"ja","ネ":"ja","ノ":"ja","ハ":"ja","ヒ":"ja","フ":"ja","ヘ":"ja","ホ":"ja","ン":"ja","가":"ko","나":"ko","다":"ko","라":"ko","마":"ko","바":"ko","사":"ko","아":"ko","자":"ko","차":"ko","카":"ko","타":"ko","파":"ko","하":"ko","는":"ko","을":"ko","에":"ko","의":"ko","한":"ko","글":"ko","어":"ko"};
function detectLanguage(text){
 const value=String(text||"").trim(), lower=value.toLowerCase(), app=selectedAppLanguage();
 if(!value)return app;
 // Identify writing systems before Latin-language keyword checks.
 if(/[\u3040-\u30ff]/u.test(value))return "ja";
 if(/[\uac00-\ud7af]/u.test(value))return "ko";
 if(/[\u4e00-\u9fff]/u.test(value))return "zh";
 if(/[\u0e00-\u0e7f]/u.test(value))return "th";
 if(/[\u0980-\u09ff]/u.test(value))return "bn";
 if(/[\u0a80-\u0aff]/u.test(value))return "gu";
 if(/[\u0a00-\u0a7f]/u.test(value))return "pa";
 if(/[\u0c80-\u0cff]/u.test(value))return "kn";
 if(/[\u0d00-\u0d7f]/u.test(value))return "ml";
 if(/[\u0b80-\u0bff]/u.test(value))return "ta";
 if(/[\u0c00-\u0c7f]/u.test(value))return "te";
 if(/[\u0370-\u03ff]/u.test(value))return "el";
 if(/[\u0400-\u04ff]/u.test(value))return /[іїєґў]/iu.test(value)?"uk":"ru";
 if(/[\u0600-\u06ff]/u.test(value)){
  if(/[ٹڈڑںھہے]/u.test(value))return "ur";
  if(/[پچژگ]/u.test(value))return "fa";
  return "ar";
 }
 if(/[\u0900-\u097f]/u.test(value)){
  if(/(तपाईं|तपाईँ|छु|छैन|गर्नु|हुनुहुन्छ|नेपाल|धन्यवाद|मलाई|मेरो|तपाईँको)/u.test(value))return "ne";
  if(/(हैं|हूँ|होगा|करें|कृपया|मुझे|आपका|क्यों|कैसे|नहीं)/u.test(value))return "hi";
  return app==="ne"?"ne":"hi";
 }
 if(/[a-zA-Z]/u.test(value)){
  // Detect common romanized South Asian speech before defaulting Latin text to English.
  if(/\b(ma|mero|malai|tapai|timi|chha|chaina|gar|garna|gara|banau|banaideu|kasto|kina|kasari|hoina|dhanyabad|namaste|tasbir|tasbira|nepali)\b/u.test(lower))return "ne";
  if(/\b(kya|hai|hain|mujhe|mera|meri|aap|tum|kaise|kyun|banao|bana do|tasveer|chahiye|namaste)\b/u.test(lower))return "hi";
  if(/\b(hola|gracias|porque|quiero|cómo|qué|buenos|buenas|estoy|usted)\b/u.test(lower))return "es";
  if(/\b(bonjour|merci|pourquoi|comment|avec|vous|je suis|salut)\b/u.test(lower))return "fr";
  if(/\b(hallo|danke|warum|bitte|und|ich|nicht|guten)\b/u.test(lower))return "de";
  if(/\b(olá|obrigado|obrigada|você|vocês|não|estou|como)\b/u.test(lower))return "pt";
  if(/\b(ciao|grazie|perché|come|sono|buongiorno)\b/u.test(lower))return "it";
  if(/\b(merhaba|teşekkür|nasıl|neden|lütfen|ben|sen)\b/u.test(lower))return "tr";
  if(/\b(halo|terima kasih|bagaimana|saya|kamu|tidak|selamat)\b/u.test(lower))return "id";
  if(/\b(xin chào|cảm ơn|không|tôi|bạn|được|đang)\b/u.test(lower))return "vi";
  if(/\b(asante|habari|tafadhali|ndiyo|hapana|karibu|nina)\b/u.test(lower))return "sw";
  if(/\b(alsjeblieft|dank je|bedankt|waarom|hoe|niet|ik ben)\b/u.test(lower))return "nl";
  if(/\b(dziękuję|proszę|dlaczego|jak|jestem|nie|cześć)\b/u.test(lower))return "pl";
  return "en";
 }
 return app;
}
function addMessage(text,role){const wrap=document.createElement("div");wrap.className="msg "+(role==="user"?"user":"assistant");const body=document.createElement("div");body.textContent=text;wrap.append(body);if(role==="assistant"){const actions=document.createElement("div");actions.className="msg-actions";const speakBtn=document.createElement("button");speakBtn.type="button";speakBtn.textContent="🔊 Read aloud";speakBtn.addEventListener("click",()=>speak(text,lastLanguage));const copy=document.createElement("button");copy.type="button";copy.textContent="Copy";copy.addEventListener("click",()=>navigator.clipboard?.writeText(text).then(()=>notice.textContent="Copied.").catch(()=>notice.textContent="Copy unavailable."));actions.append(speakBtn,copy);wrap.append(actions);}log.append(wrap);wrap.scrollIntoView({block:"end",behavior:"smooth"});return body;}
let activeAudio=null;
async function speak(text,language){
 if(text.length<=3500){
  try{
   voiceStatus.textContent="Preparing a spoken reply in the same language…";
   const result=await invoke({action:"speak",text});
   if(activeAudio){activeAudio.pause();activeAudio=null;}
   activeAudio=new Audio("data:"+result.mimeType+";base64,"+result.audio);
   await activeAudio.play();
   voiceStatus.textContent="Playing spoken reply.";
   activeAudio.onended=()=>{voiceStatus.textContent="Spoken reply finished.";};
   return;
  }catch(error){voiceStatus.textContent="AI voice unavailable; trying a voice installed on this device.";}
 }
 if(!("speechSynthesis"in window)){voiceStatus.textContent="Spoken reply is unavailable on this device; the written answer is available.";return;}
 speechSynthesis.cancel();
 const utterance=new SpeechSynthesisUtterance(text);
 utterance.lang=language||lastLanguage||"en";
 const voices=speechSynthesis.getVoices();
 const match=voices.find(v=>v.lang.toLowerCase().startsWith(utterance.lang.toLowerCase()))||voices.find(v=>v.lang.toLowerCase().startsWith(utterance.lang.split("-")[0].toLowerCase()));
 if(match)utterance.voice=match;
 else voiceStatus.textContent="No installed device voice for "+utterance.lang+". The written answer is available.";
 speechSynthesis.speak(utterance);
}
async function session(){const client=db();if(!client?.auth)throw new Error("Supabase connection not ready. Reload this page.");const {data,error}=await client.auth.getSession();if(error||!data?.session)throw new Error("Please sign in to use Creator AI Studio.");return client;}
async function invoke(body){
 const client=await session();
 const {data,error}=await client.functions.invoke("ai-gateway",{body});
 if(error){
  let detail="";
  try{
   const response=error.context;
   if(response?.clone){
    const payload=await response.clone().json();
    detail=String(payload?.error||payload?.message||"");
   }
  }catch(_){}
  if(body.action==="image")throw new Error(detail||"अभी तस्वीर नहीं बन पाई। थोड़ी देर बाद फिर कोशिश करें।");
  if(body.action==="transcribe")throw new Error(detail||"आवाज़ समझ नहीं आई। माइक की अनुमति जाँचकर दोबारा बोलें या संदेश लिखें।");
  if(body.action==="speak")throw new Error(detail||"अभी आवाज़ में जवाब उपलब्ध नहीं है। लिखित जवाब उपलब्ध रहेगा।");
  throw new Error(detail||"AI अभी उपलब्ध नहीं है। कृपया थोड़ी देर बाद फिर कोशिश करें।");
 }
 if(!data?.ok){
  if(body.action==="image")throw new Error(data?.error||"तस्वीर बनाने की सेवा अभी उपलब्ध नहीं है। कृपया थोड़ी देर बाद फिर कोशिश करें।");
  if(body.action==="transcribe")throw new Error(data?.error||"आवाज़ को टेक्स्ट में नहीं बदला जा सका। माइक की अनुमति जाँचकर फिर कोशिश करें।");
  if(body.action==="speak")throw new Error(data?.error||"अभी आवाज़ में जवाब उपलब्ध नहीं है। लिखित जवाब उपलब्ध रहेगा।");
  if(body.action==="status")throw new Error(data?.error||"AI स्थिति अभी जाँची नहीं जा सकी।");
  throw new Error(data?.error||"AI अभी उपलब्ध नहीं है। कृपया थोड़ी देर बाद फिर कोशिश करें।");
 }
 return data;
}
function isVideoRequest(text){
 const value=String(text||"").trim();
 const hasVideo=/\b(?:video|clip|movie|animation|reel)\b/i.test(value)||/(?:वीडियो|चलचित्र|भिडियो|चलती तस्वीर)/u.test(value);
 const hasIntent=/\b(?:generate|create|make|animate|render|produce|bana\w*|banau\w*|banai\w*|chahiye)\b/i.test(value)||/(?:बना|बनाओ|बनाइ|तैयार|जनरेट|चाहिए)/u.test(value);
 return hasVideo&&hasIntent;
}
function isImageRequest(text){
 const value=String(text||"").trim();
 if(!value)return false;
 const hasImageTerm=/\b(?:image|images|picture|pictures|photo|photos|photograph|thumbnail|logo|artwork|drawing|tasvir|tasveer|tasbir|tasbira|taswira|chitra|foto)\b/i.test(value) ||
  /(?:तस्वीर|तस्बिर|फोटो|चित्र|इमेज|छवि|लोगो)/u.test(value);
 const hasCreateIntent=/\b(?:generate|create|make|draw|paint|render|show|bana\w*|banau\w*|banai\w*|banadeu\w*|banado\w*|banaideu\w*|banauna\w*|dikha\w*|chahiye|chahie|create|generate)\b/i.test(value) ||
  /(?:बना|बनाओ|बनाइ|तैयार|जनरेट|दिखा|चाहिए)/u.test(value);
 // Route romanized Hindi/Nepali requests such as "tasvir Nahin banaa sakte ho"
 // directly to the image API instead of letting chat text refuse the request.
 if(hasImageTerm&&hasCreateIntent)return true;
 return /\b(generate|create|make|draw|paint|render|show)\s+(?:me\s+)?(?:an?\s+)?(?:image|picture|photo|illustration|thumbnail|logo)\b/i.test(value) ||
  /(?:तस्वीर|तस्बिर|फोटो|चित्र|इमेज|छवि|लोगो).*(?:बना|बनाओ|बनाइ|तैयार|जनरेट|दिखा|चाहिए)|(?:बना|बनाओ|बनाइ|तैयार|जनरेट|दिखा|चाहिए).*(?:तस्वीर|तस्बिर|फोटो|चित्र|इमेज|छवि|लोगो)/u.test(value);
}
async function sendMessage(){const text=prompt.value.trim();if(!text||busy)return;if(videoMode||isVideoRequest(text)){await generateVideo(text);return;}if(imageMode||isImageRequest(text)){await generateImage(text);return;}busy=true;send.disabled=true;voice.disabled=true;lastLanguage=detectLanguage(text);addMessage(text,"user");prompt.value="";const pending=addMessage("…","assistant");try{const result=await invoke({messages:[...history.slice(-8),{role:"user",content:text}],language:"auto"});pending.textContent=result.answer;history.push({role:"user",content:text},{role:"assistant",content:result.answer});if(history.length>10)history=history.slice(-10);if($("speak-replies").checked)speak(result.answer,lastLanguage);}catch(e){pending.textContent=e.message||"AI unavailable. Please try again.";if(!prompt.value.trim())prompt.value=text;}finally{busy=false;send.disabled=false;voice.disabled=false;prompt.focus();}}
function requestedImageAspectRatio(description){
 const allowed=["1:1","3:4","4:3","9:16","16:9"];
 const match=description.match(/\b(\d{2,5})\s*[x×]\s*(\d{2,5})\b/i)||description.match(/\b(\d{1,2})\s*:\s*(\d{1,2})\b/);
 if(match){
  const ratio=Number(match[1])/Number(match[2]);
  if(Number.isFinite(ratio)&&ratio>0)return allowed.reduce((best,item)=>{
   const [w,h]=item.split(":").map(Number);
   const [bw,bh]=best.split(":").map(Number);
   return Math.abs(Math.log(ratio/(w/h)))<Math.abs(Math.log(ratio/(bw/bh)))?item:best;
  },"1:1");
 }
 const picker=$("image-aspect-ratio");
 return allowed.includes(picker?.value)?picker.value:"1:1";
}
async function generateImage(overrideDescription){const description=(typeof overrideDescription==="string"?overrideDescription:prompt.value).trim();if(busy)return;if(!description){notice.textContent="पहले तस्वीर का विवरण लिखें, फिर Generate image दबाएँ।";prompt.focus();return;}busy=true;send.disabled=true;voice.disabled=true;lastLanguage=detectLanguage(description);addMessage("🎨 "+description,"user");prompt.value="";imageResult.hidden=false;imageResult.replaceChildren();const label=document.createElement("p");label.textContent="Creating your image…";imageResult.append(label);try{const aspectRatio=requestedImageAspectRatio(description);const imageSize=$("image-resolution")?.value||"1K";const result=await invoke({action:"image",prompt:description,language:"auto",aspectRatio,imageSize});if(typeof result.image!=="string"||result.image.length<100)throw new Error("Image provider returned no usable image data.");const mime=/^image\/(png|jpeg|webp)$/i.test(result.mimeType||"")?result.mimeType:"image/png";const img=document.createElement("img");img.alt="AI generated creator image";img.src="data:"+mime+";base64,"+result.image;img.onerror=()=>{label.textContent="Image returned, but this device could not display it. Please retry.";};const save=document.createElement("a");save.href=img.src;save.download="new-star-creator-image."+({"image/jpeg":"jpg","image/webp":"webp"}[mime]||"png");save.textContent="Save image";save.style.color="#ff9bb0";imageResult.replaceChildren(img,save);}catch(e){label.textContent=e.message||"Image generation failed.";}finally{busy=false;send.disabled=false;voice.disabled=false;}}
async function generateVideo(overrideDescription){
 const description=(typeof overrideDescription==="string"?overrideDescription:prompt.value).trim();
 if(busy)return;
 if(!description){notice.textContent="पहले वीडियो का विवरण लिखें, फिर Generate video दबाएँ।";prompt.focus();return;}
 busy=true;send.disabled=true;voice.disabled=true;
 lastLanguage=detectLanguage(description);addMessage("🎬 "+description,"user");prompt.value="";
 videoResult.hidden=false;videoResult.replaceChildren();
 const label=document.createElement("p");label.textContent="Creating your video… इसमें कुछ समय लग सकता है।";videoResult.append(label);
 try{
  const aspectRatio=$("video-aspect-ratio")?.value||"16:9";
  const resolution=$("video-resolution")?.value||"720p";
  const duration="6s";
  const started=await invoke({action:"video",prompt:description,aspectRatio,resolution,duration});
  if(typeof started.jobId!=="string"||!started.jobId)throw new Error("The video service did not return a job ID.");
  let result=null;
  for(let attempt=0;attempt<60;attempt++){
   label.textContent="Video is rendering in the background… checking progress ("+(attempt+1)+"/60).";
   await new Promise(resolve=>setTimeout(resolve,5000));
   const status=await invoke({action:"video-status",jobId:started.jobId});
   if(status.status==="completed"){result=status;break;}
   if(status.status==="failed")throw new Error(status.error||"Video generation failed.");
  }
  if(!result)throw new Error("Video is still rendering. Please try checking again shortly.");
  if(typeof result.videoUrl!=="string"||!result.videoUrl.startsWith("https://"))throw new Error("New Star storage did not return a secure video link.");
  const player=document.createElement("video");player.controls=true;player.playsInline=true;player.preload="metadata";player.src=result.videoUrl;player.style.width="100%";player.style.maxWidth="720px";player.style.borderRadius="14px";
  const save=document.createElement("a");save.href=result.videoUrl;save.download="new-star-ai-video.mp4";save.target="_blank";save.rel="noopener";save.textContent="Open / save video";save.style.color="#ff9bb0";
  const expiry=document.createElement("small");expiry.textContent="Secure video link is available for about 1 hour. Save the video before the link expires.";
  videoResult.replaceChildren(player,save,expiry);
 }catch(e){label.textContent=e.message||"Video generation failed. Please try again."; }
 finally{busy=false;send.disabled=false;voice.disabled=false;}
}
async function handleSendAction(){
 if(recording&&browserRecognition){
  pendingSendAfterTranscription=true;send.disabled=true;
  voiceStatus.textContent="आवाज़ पहचानकर संदेश भेजा जा रहा है…";
  try{browserRecognition.stop();}catch(_){pendingSendAfterTranscription=false;send.disabled=false;}
  return;
 }
 if(recording&&recorder?.state!=="inactive"){
  pendingSendAfterTranscription=true;send.disabled=true;
  voiceStatus.textContent="रिकॉर्डिंग रोककर संदेश भेजा जा रहा है…";
  recorder.stop();
  return;
 }
 if(videoMode) await generateVideo(); else if(imageMode) await generateImage(); else await sendMessage();
}
send.addEventListener("click",handleSendAction);
prompt.addEventListener("keydown",e=>{if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();handleSendAction();}});
$("image-mode").addEventListener("click",()=>{imageMode=!imageMode;videoMode=false;$("image-mode").classList.toggle("active",imageMode);$("video-mode").classList.remove("active");$("video-options").hidden=true;send.textContent=imageMode?uiText("ai.generateImage","Generate image"):uiText("ai.send","Send");prompt.placeholder=imageMode?uiText("ai.imagePlaceholder","Describe the image you want to create…"):uiText("ai.askPlaceholder","Ask anything or describe your video idea…");notice.textContent=imageMode?uiText("ai.imageNotice","Image generation uses the configured image provider."):uiText("ai.signinNotice","Sign in required. Do not enter passwords, OTPs, API keys or private financial details.");});
$("video-mode").addEventListener("click",()=>{videoMode=!videoMode;imageMode=false;$("video-mode").classList.toggle("active",videoMode);$("image-mode").classList.remove("active");$("video-options").hidden=!videoMode;send.textContent=videoMode?"Generate video":uiText("ai.send","Send");prompt.placeholder=videoMode?"Describe the video you want to create…":uiText("ai.askPlaceholder","Ask anything or describe your video idea…");notice.textContent=videoMode?"Video generation uses a separate video model and may consume paid provider quota.":"Sign in required. Do not enter passwords, OTPs, API keys or private financial details.";});
document.querySelectorAll("[data-starter]").forEach(btn=>btn.addEventListener("click",()=>{imageMode=false;videoMode=false;$("image-mode").classList.remove("active");$("video-mode").classList.remove("active");$("video-options").hidden=true;send.textContent="Send";prompt.value=btn.dataset.starter;prompt.focus();}));
async function startRecording(forceRecorder=false){
 if(busy||recording)return;
 const SpeechRecognition=window.SpeechRecognition||window.webkitSpeechRecognition;
 if(!forceRecorder&&SpeechRecognition){
  try{
   browserRecognition=new SpeechRecognition();
   browserRecognition.lang=selectedAppLanguage()==="ne"?"ne-NP":selectedAppLanguage()==="hi"?"hi-IN":selectedAppLanguage()==="en"?"en-US":selectedAppLanguage();
   browserRecognition.continuous=true;browserRecognition.interimResults=true;browserRecognition.maxAlternatives=1;browserTranscript="";browserInterimTranscript="";voiceDraftPrefix=prompt.value.trim();
   browserRecognition.onresult=event=>{
    let finalText="";
    const interimParts=[];
    for(let i=0;i<event.results.length;i++){
     const part=String(event.results[i][0]?.transcript||"").trim();
     if(!part)continue;
     if(event.results[i].isFinal)finalText+=" "+part;
     else interimParts.push(part);
    }
    if(finalText)browserTranscript=(browserTranscript+" "+finalText).trim();
    browserInterimTranscript=interimParts.join(" ").trim();
    const liveText=[browserTranscript,browserInterimTranscript].filter(Boolean).join(" ").trim();
    if(liveText)prompt.value=[voiceDraftPrefix,liveText].filter(Boolean).join(" ");
    voiceStatus.textContent=liveText?"🔴 बोलते ही टेक्स्ट लिखा जा रहा है: "+liveText:"🔴 माइक सुन रहा है…";
   };
   browserRecognition.onerror=event=>{
    const reason=event.error||"unknown";
    recording=false;browserRecognition=null;pendingSendAfterTranscription=false;stopVoice.hidden=true;voice.textContent="🎙️";
    if(reason==="not-allowed"||reason==="service-not-allowed"){
     voiceStatus.textContent="माइक की अनुमति नहीं मिली। Android/Chrome की Site settings में Microphone अनुमति दें।";
     send.disabled=false;voice.disabled=false;
     return;
    }
    voiceStatus.textContent="ब्राउज़र voice recognition उपलब्ध नहीं हुआ ("+reason+") — रिकॉर्डिंग मोड से कोशिश कर रहे हैं…";
    send.disabled=false;voice.disabled=false;
    setTimeout(()=>startRecording(true),150);
   };
   browserRecognition.onend=async()=>{
    const transcript=(browserTranscript||browserInterimTranscript).trim();
    const shouldSend=pendingSendAfterTranscription;
    browserRecognition=null;recording=false;
    voice.textContent="🎙️";voice.setAttribute("aria-label","माइक से बोलें");stopVoice.hidden=true;
    if(transcript){
     prompt.value=[voiceDraftPrefix,transcript].filter(Boolean).join(" ");lastLanguage=detectLanguage(transcript);
     voiceStatus.textContent=shouldSend?"आवाज़ पहचान ली गई। संदेश भेजा जा रहा है…":"आवाज़ टेक्स्ट बॉक्स में लिख दी गई है।";
     pendingSendAfterTranscription=false;
     if(shouldSend) await sendMessage();
    }else{
     voiceStatus.textContent="ब्राउज़र ने शब्द नहीं पहचाने। रिकॉर्डिंग मोड शुरू किया जा रहा है…";
     pendingSendAfterTranscription=shouldSend;
     if(!busy) await startRecording(true);
    }
    if(!busy){send.disabled=false;voice.disabled=false;}
   };
   browserRecognition.start();recording=true;voice.textContent="■";voice.setAttribute("aria-label","रिकॉर्डिंग रोकें");stopVoice.hidden=false;
   voiceStatus.textContent="माइक चालू है। बोलें; रोकने या Send दबाने पर पहचाने गए शब्द AI को भेजे जाएँगे।";
   return;
  }catch(error){browserRecognition=null;recording=false;voiceStatus.textContent="इस ब्राउज़र में voice recognition शुरू नहीं हुआ; रिकॉर्डिंग मोड आज़माया जा रहा है।";}
 }
 if(!window.isSecureContext||!navigator.mediaDevices?.getUserMedia||!window.MediaRecorder){
  voiceStatus.textContent="इस पेज पर सुरक्षित microphone recording उपलब्ध नहीं है। HTTPS पर New Star खोलें या संदेश लिखकर भेजें।";
  return;
 }
 try{
  stream=await navigator.mediaDevices.getUserMedia({audio:{echoCancellation:true,noiseSuppression:true,autoGainControl:true}});
  const formats=["audio/ogg;codecs=opus","audio/webm;codecs=opus","audio/mp4","audio/webm","audio/ogg"];
  const supported=formats.find(type=>MediaRecorder.isTypeSupported?.(type));
  recorder=new MediaRecorder(stream,supported?{mimeType:supported}:{});
  chunks=[];voiceDraftPrefix=prompt.value.trim();
  recorder.ondataavailable=e=>{if(e.data?.size)chunks.push(e.data);};
  recorder.onerror=e=>{
   voiceStatus.textContent=e.error?.message||"माइक रिकॉर्डिंग में समस्या आई। फिर कोशिश करें।";
   pendingSendAfterTranscription=false;
   stream?.getTracks().forEach(track=>track.stop());stream=null;
   recording=false;send.disabled=false;voice.disabled=false;stopVoice.hidden=true;
  };
  recorder.onstop=async()=>{
   const stoppedRecorder=recorder;
   const recordedChunks=chunks.slice();
   const shouldSend=pendingSendAfterTranscription;
   pendingSendAfterTranscription=false;
   recording=false;stopVoice.hidden=true;voice.textContent="🎙️";
   voice.setAttribute("aria-label","माइक से बोलें");
   stream?.getTracks().forEach(track=>track.stop());stream=null;
   voice.disabled=true;send.disabled=true;
   const blob=new Blob(recordedChunks,{type:stoppedRecorder?.mimeType||recordedChunks[0]?.type||"audio/webm"});
   chunks=[];
   try{
    if(!blob.size)throw new Error("रिकॉर्डिंग खाली है। माइक दबाकर फिर बोलें।");
    if(blob.size>7*1024*1024)throw new Error("रिकॉर्डिंग 7 MB से बड़ी है। छोटा संदेश रिकॉर्ड करके भेजें।");
    voiceStatus.textContent="आवाज़ को टेक्स्ट में बदला जा रहा है…";
    const base64=await new Promise((resolve,reject)=>{
     const reader=new FileReader();
     reader.onload=()=>{const value=String(reader.result||"");const comma=value.indexOf(",");if(comma<0)reject(new Error("रिकॉर्डिंग पढ़ी नहीं जा सकी।"));else resolve(value.slice(comma+1));};
     reader.onerror=()=>reject(new Error("रिकॉर्डिंग पढ़ी नहीं जा सकी।"));
     reader.readAsDataURL(blob);
    });
    const mimeType=(blob.type||"audio/webm").split(";")[0].trim().toLowerCase();
    const result=await invoke({action:"transcribe",audio:base64,mimeType});
    const transcript=String(result.transcript||"").trim();
    if(!transcript)throw new Error("आवाज़ से कोई शब्द नहीं मिला। थोड़ा साफ़ बोलकर फिर कोशिश करें।");
    lastLanguage=result.language&&result.language!=="und"?result.language:detectLanguage(transcript)||lastLanguage;
    prompt.value=[voiceDraftPrefix,transcript].filter(Boolean).join(" ");
    voiceStatus.textContent=shouldSend?"आवाज़ पहचान ली गई। संदेश भेजा जा रहा है…":"आवाज़ टेक्स्ट बॉक्स में लिख दी गई है।";
    prompt.focus();
    if(shouldSend) await sendMessage();
   }catch(e){
    voiceStatus.textContent=e.message||"आवाज़ को टेक्स्ट में नहीं बदला जा सका। आप लिखकर भेज सकते हैं।";
   }finally{
    if(!busy){send.disabled=false;voice.disabled=false;}
   }
  };
  recorder.start();
  recording=true;voice.textContent="■";
  voice.setAttribute("aria-label","रिकॉर्डिंग रोकें");
  stopVoice.hidden=false;
  voiceStatus.textContent="आपकी आवाज़ रिकॉर्ड हो रही है। रोकने के लिए माइक या Stop recording दबाएँ; Send दबाने पर रिकॉर्डिंग रुककर संदेश AI को भेजा जाएगा।";
 }catch(e){
  stream?.getTracks().forEach(track=>track.stop());stream=null;
  recording=false;voice.disabled=false;send.disabled=false;stopVoice.hidden=true;
  voiceStatus.textContent=e.name==="NotAllowedError"?"माइक की अनुमति नहीं मिली। Android/App की microphone permission अनुमति दें।":e.name==="NotFoundError"?"इस डिवाइस पर माइक नहीं मिला।":e.name==="NotReadableError"?"माइक किसी दूसरे ऐप में व्यस्त है।":(e.message||"माइक शुरू नहीं हो सका।");
 }
}
voice.addEventListener("click",()=>{if(recording){if(browserRecognition){try{browserRecognition.stop();}catch(_){}}else if(recorder?.state!=="inactive")recorder.stop();return;}startRecording(false);});
stopVoice.addEventListener("click",()=>{if(recording){if(browserRecognition){try{browserRecognition.stop();}catch(_){}}else if(recorder?.state!=="inactive")recorder.stop();}});

(async()=>{try{await session();const ready=await invoke({action:"status"});const chatReady=Boolean(ready.providers?.gemini||ready.providers?.openai||ready.providers?.openrouter);const voiceReady=Boolean(ready.providers?.gemini||ready.providers?.openai);status.textContent="";notice.textContent=!chatReady?"AI सेवा अभी तैयार नहीं है। सर्वर पर AI provider सेटअप की जाँच ज़रूरी है.":voiceReady?"AI तैयार है। आप लिखकर या माइक से पूछ सकते हैं और तस्वीर बनाने को भी कह सकते हैं।":"AI चैट तैयार है, लेकिन voice transcription provider उपलब्ध नहीं है।";}catch(e){status.textContent="";notice.textContent=e.message||"AI सेवा अभी उपलब्ध नहीं है। कृपया बाद में फिर कोशिश करें।";}if("speechSynthesis"in window)speechSynthesis.onvoiceschanged=()=>{};})();
window.addEventListener("pagehide",()=>{if(recording&&recorder?.state!=="inactive")recorder.stop();stream?.getTracks().forEach(t=>t.stop());});
})();