// ⚡ Central Supabase Configuration for New Star Creator Hub
const SUPABASE_URL = "https://uzsusgoetrkujmofxcmb.supabase.co";
const SUPABASE_ANON_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr";

// Initialize Global Supabase Client with Realtime Optimization
const { createClient } = window.supabase;
const supabaseClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY, {
    auth: { persistSession: true, autoRefreshToken: true },
    realtime: { timeout: 30000 }
});
const cloudClient = supabaseClient;

// Global Fast Toast Notification Engine for all connected pages
function showGlobalFastToast(message) {
    let existingToast = document.getElementById('globalFastToast');
    if (!existingToast) {
        let toast = document.createElement('div');
        toast.id = 'globalFastToast';
        toast.style.cssText = "position:fixed; bottom:90px; left:50%; transform:translateX(-50%); background:rgba(20,20,25,0.95); border:1px solid #25f4ee; color:#fff; padding:10px 22px; border-radius:30px; font-size:12px; z-index:999999; box-shadow:0 5px 20px rgba(0,0,0,0.8); transition:0.3s ease; pointer-events:none;";
        document.body.appendChild(toast);
        existingToast = toast;
    }
    existingToast.innerText = message;
    existingToast.style.opacity = '1';
    setTimeout(() => { existingToast.style.opacity = '0'; }, 2200);
}
