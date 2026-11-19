import "jsr:@supabase/functions-js/edge-runtime.d.ts";
import { createClient } from "https://esm.sh/@supabase/supabase-js@2";

const ALLOWED_ORIGINS = new Set(["https://new-star-nepal.netlify.app","https://new-star-delta.vercel.app","https://new-star-rkdaifilms-8858.vercel.app"]);
function corsHeaders(req: Request) {
  const origin=req.headers.get("Origin")||"";
  return {"Access-Control-Allow-Origin":ALLOWED_ORIGINS.has(origin)?origin:"https://new-star-nepal.netlify.app","Access-Control-Allow-Headers":"authorization, apikey, content-type, x-client-info","Access-Control-Allow-Methods":"POST, OPTIONS","Content-Type":"application/json","Cache-Control":"no-store","Vary":"Origin"};
}
function objectPath(value: unknown): string|null {
  if(typeof value!=="string"||value.length>4096)return null;
  if(value.startsWith("storage://videos/")){const p=value.slice(17);return p&&!p.startsWith("/")&&!p.split("/").includes("..")?p:null;}
  try{const u=new URL(value);if(u.protocol!=="https:"||!u.hostname.endsWith(".supabase.co"))return null;const marker="/storage/v1/object/public/videos/";const i=u.pathname.indexOf(marker);if(i<0)return null;const p=decodeURIComponent(u.pathname.slice(i+marker.length));return p&&!p.startsWith("/")&&!p.split("/").includes("..")?p:null;}catch{return null;}
}
Deno.serve(async(req:Request)=>{
 const cors=corsHeaders(req);const respond=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:cors});
 if(req.method==="OPTIONS")return new Response("ok",{headers:cors});
 if(req.method!=="POST")return respond({ok:false,error:"Method not allowed"},405);
 const origin=req.headers.get("Origin")||"";if(origin&&!ALLOWED_ORIGINS.has(origin))return respond({ok:false,error:"Origin not allowed"},403);
 const url=Deno.env.get("SUPABASE_URL"),anon=Deno.env.get("SUPABASE_ANON_KEY"),service=Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
 if(!url||!anon||!service)return respond({ok:false,error:"Server configuration incomplete"},503);
 let body:{video_ids?:unknown};try{body=await req.json();}catch{return respond({ok:false,error:"Invalid JSON"},400);}
 if(!Array.isArray(body.video_ids)||body.video_ids.length<1||body.video_ids.length>50)return respond({ok:false,error:"Provide between 1 and 50 video IDs"},400);
 const ids=[...new Set(body.video_ids.filter((id):id is string=>typeof id==="string"&&/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id)))];
 if(ids.length!==body.video_ids.length)return respond({ok:false,error:"Invalid video ID"},400);
 let viewer:string|null=null;const auth=req.headers.get("Authorization")||"";
 if(auth.startsWith("Bearer ")){const uc=createClient(url,anon,{auth:{persistSession:false,autoRefreshToken:false},global:{headers:{Authorization:auth}}});const {data,error}=await uc.auth.getUser();if(!error&&data.user)viewer=data.user.id;}
 const admin=createClient(url,service,{auth:{persistSession:false,autoRefreshToken:false}});
 const {data:rows,error:qerr}=await admin.from("videos").select("id,user_id,video_url,video_provider,provider_video_id,processing_status,provider_status,visibility,audience_type").in("id",ids);
 if(qerr)return respond({ok:false,error:"Could not load video access rules"},500);
 const ownerIds=[...new Set((rows||[]).map(r=>String(r.user_id)))];
 const [{data:profiles,error:perr},{data:follows,error:ferr}]=await Promise.all([
  admin.from("profiles").select("user_id,is_private").in("user_id",ownerIds),
  viewer?admin.from("followers").select("following_id").eq("follower_id",viewer).in("following_id",ownerIds):Promise.resolve({data:[],error:null})
 ]);
 if(perr||ferr)return respond({ok:false,error:"Could not verify account privacy"},500);
 const privateOwners=new Set((profiles||[]).filter(p=>p.is_private===true).map(p=>String(p.user_id)));
 const approved=new Set((follows||[]).map(f=>String(f.following_id)));
 const result:Record<string,string>={};
 for(const row of rows||[]){
  const owner=String(row.user_id),isOwner=!!viewer&&owner===viewer;
  const published=(row.processing_status==null||row.processing_status==="published")&&(row.visibility==null||row.visibility==="public"||row.visibility==="unlisted")&&(row.audience_type==null||row.audience_type==="all");
  const accountAllows=!privateOwners.has(owner)||(!!viewer&&approved.has(owner));
  if(!isOwner&&(!published||!accountAllows))continue;
  if(row.video_provider==="cloudflare"&&typeof row.provider_video_id==="string"&&row.provider_video_id){
   const accountId=Deno.env.get("CLOUDFLARE_ACCOUNT_ID");
   const streamToken=Deno.env.get("CLOUDFLARE_STREAM_TOKEN");
   if(!accountId||!streamToken)continue;
   try{
    const streamResponse=await fetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/stream/${encodeURIComponent(row.provider_video_id)}`,{
     headers:{"Authorization":`Bearer ${streamToken}`}
    });
    if(!streamResponse.ok){console.error("Cloudflare stream status failed:",streamResponse.status);continue;}
    const streamPayload=await streamResponse.json();
    const streamStatus=streamPayload?.result?.status||{};
    if(streamPayload?.result?.readyToStream===true){
     if(row.processing_status!=="published"||row.provider_status!=="ready"){
      const {error:updateError}=await admin.from("videos").update({processing_status:"published",provider_status:"ready"}).eq("id",row.id);
      if(updateError)console.error("Could not publish ready Cloudflare video:",updateError.message);
     }
     const tokenResponse=await fetch(`https://api.cloudflare.com/client/v4/accounts/${accountId}/stream/${encodeURIComponent(row.provider_video_id)}/token`,{
      method:"POST",headers:{"Authorization":`Bearer ${streamToken}`,"Content-Type":"application/json"},
      body:JSON.stringify({exp:Math.floor(Date.now()/1000)+3600})
     });
     if(!tokenResponse.ok){console.error("Cloudflare playback token failed:",tokenResponse.status);continue;}
     const tokenPayload=await tokenResponse.json();
     const playbackToken=tokenPayload?.result?.token;
     if(typeof playbackToken==="string"&&playbackToken){
      result[String(row.id)]=`https://videodelivery.net/${encodeURIComponent(playbackToken)}/manifest/video.m3u8`;
     }
    }else if(streamStatus.state==="error"||streamStatus.errorReasonCode){
     const {error:updateError}=await admin.from("videos").update({processing_status:"failed",provider_status:"error"}).eq("id",row.id);
     if(updateError)console.error("Could not mark failed Cloudflare video:",updateError.message);
    }else{
     if(row.provider_status!=="processing"){
      const {error:updateError}=await admin.from("videos").update({provider_status:"processing"}).eq("id",row.id);
      if(updateError)console.error("Could not update Cloudflare processing state:",updateError.message);
     }
    }
   }catch(error){console.error("Cloudflare status/playback error:",error);}
   continue;
  }
  const path=objectPath(row.video_url);if(!path)continue;
  const {data,error}=await admin.storage.from("videos").createSignedUrl(path,3600);
  if(!error&&data?.signedUrl)result[String(row.id)]=data.signedUrl;
 }
 return respond({ok:true,urls:result,expires_in:3600});
});