
/* =====================================================
   NEW STAR CREATOR HUB
   GLOBAL NAVIGATION + SUPABASE + AUTH GUARD
   ===================================================== */

(function(){
 "use strict";

 if(window.__NEW_STAR_NAVBAR_STARTED__) return;
 window.__NEW_STAR_NAVBAR_STARTED__=true;

 const currentPage=location.pathname.split("/").pop() || "index.html";

 const PUBLIC_PAGES=["index.html","auth.html"];

 const PROTECTED_PAGES=[
  "profile.html",
  "camera.html",
  "upload.html",
  "wallet.html",
  "inbox.html",
  "settings.html",
  "monetization.html",
  "live.html",
  "gaming.html",
  "campaign.html"
 ];

 let authCheckFinished=false;
 let authenticated=false;
 let authClient=null;
 let redirectStarted=false;

 /* -----------------------------------------
    SUPABASE CLIENT
    ----------------------------------------- */

 function loadScript(src){
  return new Promise((resolve,reject)=>{
   const existing=[...document.scripts].find(s=>s.src===new URL(src,location.href).href);

   if(existing){
    if(existing.dataset.loaded==="yes"){
     resolve();
    }else{
     existing.addEventListener("load",resolve,{once:true});
     existing.addEventListener("error",reject,{once:true});
    }
    return;
   }

   const script=document.createElement("script");
   script.src=src;
   script.async=false;
   script.onload=()=>{
    script.dataset.loaded="yes";
    resolve();
   };
   script.onerror=()=>reject(new Error("Script load failed: "+src));
   document.head.appendChild(script);
  });
 }

 function exposeExistingClient(){
  if(window.supabaseClient){
   window.cloudClient=window.cloudClient || window.supabaseClient;
   return window.supabaseClient;
  }

  if(window.cloudClient){
   window.supabaseClient=window.cloudClient;
   return window.cloudClient;
  }

  // पुराने config में const client बनाया गया था, window पर नहीं।
  try{
   if(typeof supabaseClient!=="undefined" && supabaseClient){
    window.supabaseClient=supabaseClient;
    window.cloudClient=supabaseClient;
    return supabaseClient;
   }
  }catch(e){}

  return null;
 }

 async function ensureSupabase(){
  let client=exposeExistingClient();
  if(client) return client;

  // SDK पहले लोड करें।
  if(!window.supabase || typeof window.supabase.createClient!=="function"){
   await loadScript("https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2");
  }

  // Central config मौजूद हो तो उसे लोड करें।
  try{
   await loadScript("./supabase-config.js");
  }catch(error){
   console.warn("Central config load issue:",error);
  }

  client=exposeExistingClient();
  if(client) return client;

  // Config की पुरानी global-variable समस्या के लिए fallback।
  const url=window.NS_SUPABASE_URL ||
   (typeof SUPABASE_URL!=="undefined" ? SUPABASE_URL : "") ||
   "https://uzsusgoetrkujmofxcmb.supabase.co";

  const key=window.NS_SUPABASE_ANON_KEY ||
   (typeof SUPABASE_ANON_KEY!=="undefined" ? SUPABASE_ANON_KEY : "") ||
   "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr";

  if(!url || !key){
   throw new Error("Supabase URL या publishable key नहीं मिली।");
  }

  client=window.supabase.createClient(url,key,{
   auth:{
    persistSession:true,
    autoRefreshToken:true,
    detectSessionInUrl:true
   },
   realtime:{timeout:30000}
  });

  window.supabaseClient=client;
  window.cloudClient=client;
  return client;
 }

 function getClient(){
  return authClient || exposeExistingClient();
 }

 /* -----------------------------------------
    LOGIN REDIRECT
    ----------------------------------------- */

 function safeCurrentPage(){
  const page=location.pathname.split("/").pop() || "index.html";

  if(!PROTECTED_PAGES.includes(page)) return "index.html";

  return page+location.search+location.hash;
 }

 function goToLogin(){
  if(redirectStarted) return;
  redirectStarted=true;

  const next=safeCurrentPage();
  location.replace("auth.html?next="+encodeURIComponent(next));
 }

 /* -----------------------------------------
    PROTECTED PAGE VISIBILITY
    ----------------------------------------- */

 if(PROTECTED_PAGES.includes(currentPage)){
  document.documentElement.style.visibility="hidden";
 }

 /* -----------------------------------------
    AUTH CHECK
    ----------------------------------------- */

 async function checkAuthentication(){
  try{
   authClient=await ensureSupabase();

   const {data,error}=await authClient.auth.getSession();
   if(error) throw error;

   authenticated=!!(data && data.session);
   authCheckFinished=true;

   if(!authenticated && PROTECTED_PAGES.includes(currentPage)){
    goToLogin();
    return;
   }

   if(PROTECTED_PAGES.includes(currentPage)){
    document.documentElement.style.visibility="visible";
   }

   // Login/logout को सभी पेजों पर सुनें।
   authClient.auth.onAuthStateChange((event,session)=>{
    authenticated=!!session;

    if(event==="SIGNED_OUT" && PROTECTED_PAGES.includes(currentPage)){
     goToLogin();
    }
   });

  }catch(error){
   console.error("New Star Supabase/Auth error:",error);
   authCheckFinished=true;

   if(PROTECTED_PAGES.includes(currentPage)){
    // Protected page बिना सत्यापित login के न दिखाएँ।
    goToLogin();
   }else{
    document.documentElement.style.visibility="visible";
   }
  }
 }

 /* -----------------------------------------
    PROTECTED LINK CLICK HANDLER
    ----------------------------------------- */

 document.addEventListener("click",function(event){
  const link=event.target.closest("a[href]");
  if(!link) return;

  const href=link.getAttribute("href");

  if(!href ||
     href.startsWith("#") ||
     href.startsWith("javascript:") ||
     href.startsWith("mailto:") ||
     href.startsWith("tel:") ||
     link.target==="_blank"){
   return;
  }

  let destination;

  try{
   destination=new URL(href,location.href);
  }catch(e){
   return;
  }

  if(destination.origin!==location.origin) return;

  const page=destination.pathname.split("/").pop();
  if(!PROTECTED_PAGES.includes(page)) return;

  if(!authCheckFinished || !authenticated){
   event.preventDefault();
   event.stopImmediatePropagation();
   goToLogin();
  }
 },true);

 /* -----------------------------------------
    GUEST ACTION PROTECTION
    Home feed video watching is public.
    Like/comment/follow/gift/upload require login.
    ----------------------------------------- */

 document.addEventListener("click",function(event){
  const action=event.target.closest("[data-auth-required]");
  if(!action) return;

  if(authCheckFinished && authenticated) return;

  event.preventDefault();
  event.stopImmediatePropagation();

  if(typeof window.showGlobalFastToast==="function"){
   window.showGlobalFastToast("यह सुविधा इस्तेमाल करने के लिए Login करें।");
  }

  goToLogin();
 },true);

 /* -----------------------------------------
    REMOVE OLD NAVIGATION
    Keep auth.html own bottom nav.
    ----------------------------------------- */

 function removeOldNavigation(){
  if(currentPage==="auth.html") return;

  document.querySelectorAll(
   ".bottom-nav, .bottomNav, .app-global-bottom-nav, .master-tiktok-final-nav"
  ).forEach(element=>element.remove());
 }

 /* -----------------------------------------
    GLOBAL BOTTOM NAVIGATION
    ----------------------------------------- */

 function createBottomNavigation(){
  if(currentPage==="auth.html") return;
  if(document.getElementById("newStarBottomNav")) return;

  const nav=document.createElement("nav");
  nav.id="newStarBottomNav";
  nav.className="master-tiktok-final-nav";

  nav.innerHTML=`
   <style>
    #newStarBottomNav{
     position:fixed;
     bottom:0;left:0;width:100%;
     min-height:64px;
     padding-bottom:env(safe-area-inset-bottom);
     background:#080808;
     border-top:1px solid #292929;
     display:flex;
     justify-content:space-around;
     align-items:center;
     z-index:999999;
    }
    #newStarBottomNav .ns-nav-item{
     flex:1;height:62px;
     display:flex;flex-direction:column;
     align-items:center;justify-content:center;
     text-decoration:none;color:#888;
     font-size:11px;gap:3px;
    }
    #newStarBottomNav .ns-nav-item.active{
     color:#fff;font-weight:bold;
    }
    #newStarBottomNav .ns-nav-icon{font-size:21px}
    #newStarBottomNav .ns-nav-plus{
     width:45px;height:33px;border-radius:8px;
     display:flex;align-items:center;justify-content:center;
     background:linear-gradient(135deg,#25f4ee,#fe2c55);
     color:#fff;font-size:25px;font-weight:bold;
    }
   </style>

   <a href="index.html" class="ns-nav-item ${currentPage==="index.html"?"active":""}">
    <span class="ns-nav-icon">🏠</span>
    <span>Home</span>
   </a>

   <a href="wallet.html" class="ns-nav-item ${currentPage==="wallet.html"?"active":""}">
    <span class="ns-nav-icon">💰</span>
    <span>Wallet</span>
   </a>

   <a href="camera.html" class="ns-nav-item" aria-label="Create Video">
    <span class="ns-nav-plus">+</span>
   </a>

   <a href="inbox.html" class="ns-nav-item ${currentPage==="inbox.html"?"active":""}">
    <span class="ns-nav-icon">💬</span>
    <span>Inbox</span>
   </a>

   <a href="profile.html" class="ns-nav-item ${currentPage==="profile.html"?"active":""}">
    <span class="ns-nav-icon">👤</span>
    <span>Profile</span>
   </a>
  `;

  document.body.appendChild(nav);

  // Page content bottom nav के पीछे न छिपे।
  document.body.style.paddingBottom="75px";
 }

 /* -----------------------------------------
    PROFILE THREE-DOT MENU ONLY
    ----------------------------------------- */

 function createProfileMenu(){
  if(currentPage!=="profile.html") return;
  if(document.getElementById("nsGlobalMenu")) return;

  const wrapper=document.createElement("div");
  wrapper.id="nsGlobalMenu";

  wrapper.innerHTML=`
   <style>
    #nsMenuButton{
     position:fixed;top:12px;right:14px;
     z-index:1000001;width:42px;height:42px;
     border-radius:50%;background:#171717;color:#fff;
     border:1px solid #fe2c55;font-size:25px;cursor:pointer;
    }
    #nsMenuPanel{
     display:none;position:fixed;top:62px;right:12px;
     width:min(290px,85vw);max-height:75vh;overflow:auto;
     background:#17171d;border:1px solid #444;
     border-radius:14px;z-index:1000002;
     box-shadow:0 10px 35px #000;padding:9px;
    }
    #nsMenuPanel.open{display:block}
    .ns-menu-link{
     display:flex;align-items:center;gap:12px;
     padding:14px 12px;color:white;text-decoration:none;
     border-bottom:1px solid #303038;font-size:14px;
    }
    .ns-menu-link:last-child{border:0}
    .ns-menu-heading{
     color:#fe2c55;font-weight:bold;padding:10px;
     border-bottom:1px solid #444;
    }
    #nsMenuOverlay{
     display:none;position:fixed;inset:0;
     background:#0008;z-index:1000000;
    }
    #nsMenuOverlay.open{display:block}
   </style>

   <button id="nsMenuButton" aria-label="Profile settings">⋮</button>
   <div id="nsMenuOverlay"></div>

   <div id="nsMenuPanel">
    <div class="ns-menu-heading">⭐ PROFILE MENU</div>

    <a class="ns-menu-link" href="settings.html">⚙️ Profile Settings & Privacy</a>
    <a class="ns-menu-link" href="wallet.html">💰 Wallet & Balance</a>
    <a class="ns-menu-link" href="monetization.html">💎 Monetization</a>
    <a class="ns-menu-link" href="inbox.html">🔔 Notifications & Inbox</a>
    <a class="ns-menu-link" href="settings.html#help">❓ Help & Support</a>
    <a class="ns-menu-link" href="#" id="nsLogoutButton">🚪 Logout</a>
   </div>
  `;

  document.body.appendChild(wrapper);

  const button=document.getElementById("nsMenuButton");
  const panel=document.getElementById("nsMenuPanel");
  const overlay=document.getElementById("nsMenuOverlay");

  function closeMenu(){
   panel.classList.remove("open");
   overlay.classList.remove("open");
  }

  button.addEventListener("click",()=>{
   panel.classList.toggle("open");
   overlay.classList.toggle("open");
  });

  overlay.addEventListener("click",closeMenu);

  document.getElementById("nsLogoutButton").addEventListener("click",async event=>{
   event.preventDefault();

   const client=getClient();
   if(!client){
    location.replace("auth.html");
    return;
   }

   try{
    const {error}=await client.auth.signOut();
    if(error) throw error;
    location.replace("index.html");
   }catch(error){
    alert("Logout नहीं हो सका: "+error.message);
   }
  });
 }

 /* -----------------------------------------
    INITIALIZE
    ----------------------------------------- */

 function initializeNavigation(){
  removeOldNavigation();

  if(currentPage!=="auth.html"){
   createBottomNavigation();
   createProfileMenu();
  }
 }

 if(document.readyState==="loading"){
  document.addEventListener("DOMContentLoaded",initializeNavigation,{once:true});
 }else{
  initializeNavigation();
 }

 // Start auth check immediately.
 checkAuthentication();

})();
