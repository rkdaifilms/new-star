(()=>{"use strict";
const $=id=>document.getElementById(id);
const form=$("ns-form"),prompt=$("ns-prompt"),output=$("ns-output"),submit=$("ns-submit"),status=$("engine-status"),foot=$("ns-footnote"),fileInput=$("ns-file");
let mode="chat",busy=false,attachment=null,history=[];
const modes={chat:["AI Chat","Ask questions, plan and create."],research:["Live Research","Search the web and return source links."],image:["Image Generation","Describe an image, edit an upload, and choose output size."],video:["Video Generation","Describe a video or attach an image to animate."]};
function bubble(text,kind="ai"){const el=document.createElement("div");el.className="ns-message "+(kind==="user"?"ns-user":"ns-ai");el.textContent=text;output.append(el);output.scrollTop=output.scrollHeight;return el;}
function setMode(next){mode=next;document.querySelectorAll("[data-mode]").forEach(b=>b.classList.toggle("active",b.dataset.mode===mode));$("mode-title").textContent=modes[mode][0];$("mode-help").textContent=modes[mode][1];prompt.placeholder=mode==="image"?"Describe the image you want to generate…":mode==="video"?"Describe the video you want to create…":"Ask anything…";$("ns-size").hidden=mode!=="image";$("ns-upload").hidden=false;submit.textContent=mode==="image"?"Generate image ↗":mode==="video"?"Generate video ↗":"Send ↗";foot.textContent="Requests are routed to your private New Star AI API. Backend endpoints must be installed to run models."; }
document.querySelectorAll("[data-mode]").forEach(b=>b.addEventListener("click",()=>setMode(b.dataset.mode)));
fileInput.addEventListener("change",()=>{attachment=fileInput.files?.[0]||null;$("ns-attachment-wrap").hidden=!attachment;$("ns-attachment-name").textContent=attachment?attachment.name:"";});
$("ns-clear-file").addEventListener("click",()=>{attachment=null;fileInput.value="";$("ns-attachment-wrap").hidden=true;});
function endpoint(){return ({chat:"/api/new-star-ai/chat",research:"/api/new-star-ai/research",image:"/api/new-star-ai/images",video:"/api/new-star-ai/videos"})[mode];}
async function getSession(){const client=window.supabaseClient||window.cloudClient;if(!client?.auth)return null;const{data}=await client.auth.getSession();return data?.session||null;}
async function run(event){event?.preventDefault();const text=prompt.value.trim();if((!text&&!attachment)||busy)return;busy=true;submit.disabled=true;const sendText=text||"Please analyze the attached image.";bubble(text||"Attached image", "user");const pending=bubble(mode==="image"?"Preparing image request…":mode==="video"?"Preparing video request…":"Working…");prompt.value="";
try{
 const session=await getSession();if(!session){throw new Error("Please sign in to New Star first.");}
 const formData=new FormData();formData.append("prompt",sendText);formData.append("mode",mode);formData.append("size",$("ns-size").value);formData.append("history",JSON.stringify(history.slice(-12)));if(attachment)formData.append("image",attachment);
 const response=await fetch(endpoint(),{method:"POST",headers:{Authorization:"Bearer "+session.access_token},body:formData});
 const data=await response.json().catch(()=>({}));
 if(!response.ok||data.ok===false){if(response.status===404)throw new Error("New Star AI backend is not connected yet. The frontend is ready; add the private model server/API endpoints to enable generation.");throw new Error(data.error||"The AI request failed ("+response.status+").");}
 pending.textContent=data.answer||data.message||"Completed.";
 if(data.imageUrl){const img=document.createElement("img");img.alt="AI-generated image";img.src=data.imageUrl;pending.append(img);}
 if(data.videoUrl){const video=document.createElement("video");video.controls=true;video.playsInline=true;video.src=data.videoUrl;video.style.cssText="width:100%;max-width:640px;border-radius:12px;margin-top:10px";pending.append(video);}
 if(Array.isArray(data.sources)&&data.sources.length){const list=document.createElement("div");for(const source of data.sources){if(!source?.url)continue;const a=document.createElement("a");a.href=source.url;a.target="_blank";a.rel="noopener noreferrer";a.textContent=source.title||source.url;a.style.display="block";list.append(a);}pending.append(list);}
 if(mode==="chat"||mode==="research"){history.push({role:"user",content:sendText},{role:"assistant",content:data.answer||data.message||""});if(history.length>20)history=history.slice(-20);}
 attachment=null;fileInput.value="";$("ns-attachment-wrap").hidden=true;
}catch(error){pending.textContent=error.message||"AI backend unavailable.";pending.dataset.error="true";}
finally{busy=false;submit.disabled=false;prompt.focus();}
}
form.addEventListener("submit",run);
prompt.addEventListener("keydown",e=>{if(e.key==="Enter"&&!e.shiftKey){e.preventDefault();form.requestSubmit();}});
window.addEventListener("load",async()=>{const s=await getSession().catch(()=>null);status.textContent=s?"Signed in":"Sign in required";status.style.color=s?"#91e6b0":"#f6c66b";});
setMode("chat");
})();