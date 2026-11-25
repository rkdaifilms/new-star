export type PrivateSearchResult = { ok: true; results: Array<{ title: string; url: string; snippet: string; fetchedAt: number; source: string }> } | { ok: false; status: number; error: string };
function base(value: string): string | null {
  try { const u=new URL(value.trim()); if(u.protocol!=="https:"||u.username||u.password||u.search||u.hash)return null; return u.toString().replace(/\/$/,""); } catch { return null; }
}
async function request(baseUrl:string,key:string,path:string,body:unknown):Promise<Response>{
 const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),25000);
 try{return await fetch(baseUrl+path,{method:"POST",redirect:"error",signal:controller.signal,headers:{Authorization:"Bearer "+key,"Content-Type":"application/json"},body:JSON.stringify(body)});}
 finally{clearTimeout(timer);}
}
export async function privateSearch(baseUrl:string,key:string,query:string,limit:number):Promise<PrivateSearchResult>{
 const endpoint=base(baseUrl);if(!endpoint||key.trim().length<32)return{ok:false,status:503,error:"Private search HTTPS endpoint or API key is not configured."};
 try{const response=await request(endpoint,key,"/v1/search",{query,limit});const data=await response.json().catch(()=>({}));
 if(!response.ok)return{ok:false,status:response.status,error:typeof data?.detail==="string"?data.detail.slice(0,300):"Private search worker rejected the query."};
 if(!Array.isArray(data?.results))return{ok:false,status:502,error:"Private search worker returned an invalid result set."};
 const results=data.results.filter((x:any)=>typeof x?.title==="string"&&typeof x?.url==="string"&&typeof x?.snippet==="string"&&/^https?:\/\//i.test(x.url)).slice(0,limit).map((x:any)=>({title:x.title.slice(0,500),url:x.url.slice(0,2048),snippet:x.snippet.slice(0,1500),fetchedAt:Number(x.fetchedAt)||0,source:"new_star_private_index"}));
 return{ok:true,results};
 }catch(error){return{ok:false,status:503,error:error instanceof Error&&error.name==="AbortError"?"Private search timed out.":"Private search worker is unreachable."};}
}
export async function privateCrawl(baseUrl:string,key:string,url:string):Promise<{ok:true;title:string;url:string;charactersIndexed:number;pagesIndexed:number}|{ok:false;status:number;error:string}>{
 const endpoint=base(baseUrl);if(!endpoint||key.trim().length<32)return{ok:false,status:503,error:"Private search HTTPS endpoint or API key is not configured."};
 try{const response=await request(endpoint,key,"/v1/crawl",{url});const data=await response.json().catch(()=>({}));
 if(!response.ok)return{ok:false,status:response.status,error:typeof data?.detail==="string"?data.detail.slice(0,300):"Private crawler rejected the URL."};
 if(typeof data?.title!=="string"||typeof data?.url!=="string"||!Number.isFinite(data?.characters_indexed))return{ok:false,status:502,error:"Private crawler returned an invalid result."};
 return{ok:true,title:data.title,url:data.url,charactersIndexed:data.characters_indexed,pagesIndexed:Number(data.pages_indexed)||1};
 }catch(error){return{ok:false,status:503,error:error instanceof Error&&error.name==="AbortError"?"Private crawler timed out.":"Private crawler is unreachable."};}
}
