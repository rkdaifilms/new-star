import { readWorkerJson } from "./worker-response.ts";
export type PrivateVisionResult={ok:true;answer:string;model:string}|{ok:false;status:number;error:string};
function visionBase(value:string):string|null{try{const u=new URL(value.trim());if(u.protocol!=="https:"||u.username||u.password||u.search||u.hash)return null;return u.toString().replace(/\/$/,"");}catch{return null;}}
export async function privateVision(baseUrl:string,key:string,image:string,mimeType:string,prompt:string):Promise<PrivateVisionResult>{
 const endpoint=visionBase(baseUrl);if(!endpoint||key.trim().length<32)return{ok:false,status:503,error:"Private vision HTTPS endpoint or API key is not configured."};
 const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),110000);
 try{const response=await fetch(endpoint+"/v1/vision/analyze",{method:"POST",redirect:"error",signal:controller.signal,headers:{Authorization:"Bearer "+key,"Content-Type":"application/json"},body:JSON.stringify({image,mime_type:mimeType,prompt})});
 const parsed=await readWorkerJson(response, 64 * 1024);const data=parsed.ok?parsed.value:{};if(!response.ok)return{ok:false,status:response.status,error:typeof data?.detail==="string"?data.detail.slice(0,300):"Private vision worker rejected the image."};
 if(typeof data?.answer!=="string"||!data.answer.trim())return{ok:false,status:502,error:"Private vision worker returned an empty answer."};
 return{ok:true,answer:data.answer.slice(0,12000),model:typeof data.model==="string"?data.model:"New Star private vision model"};
 }catch(error){return{ok:false,status:503,error:error instanceof Error&&error.name==="AbortError"?"Private vision inference timed out.":"Private vision worker is unreachable."};}finally{clearTimeout(timer);}
}
