
// ⚡ Central Supabase Configuration
// New Star Creator Hub

(function () {
    "use strict";

    const SUPABASE_URL = "https://uzsusgoetrkujmofxcmb.supabase.co";
    const SUPABASE_ANON_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr";

    // Check Supabase SDK
    if (!window.supabase || typeof window.supabase.createClient !== "function") {
        console.error("Supabase SDK not loaded. Load Supabase CDN before supabase-config.js");
        return;
    }

    // Reuse existing client if already initialized
    let client = window.supabaseClient || window.cloudClient;

    if (!client) {
        client = window.supabase.createClient(
            SUPABASE_URL,
            SUPABASE_ANON_KEY,
            {
                auth: {
                    persistSession: true,
                    autoRefreshToken: true,
                    detectSessionInUrl: true
                },
                realtime: {
                    timeout: 30000
                }
            }
        );
    }

    // Make client available to every connected page
    window.supabaseClient = client;
    window.cloudClient = client;

    // Global toast notification
    window.showGlobalFastToast = function (message) {
        let toast = document.getElementById("globalFastToast");

        if (!toast) {
            toast = document.createElement("div");
            toast.id = "globalFastToast";

            toast.style.cssText = `
                position: fixed;
                bottom: 90px;
                left: 50%;
                transform: translateX(-50%);
                background: rgba(20,20,25,0.95);
                border: 1px solid #25f4ee;
                color: #fff;
                padding: 10px 22px;
                border-radius: 30px;
                font-size: 12px;
                z-index: 999999;
                box-shadow: 0 5px 20px rgba(0,0,0,0.8);
                transition: opacity 0.3s ease;
                pointer-events: none;
                opacity: 0;
            `;

            document.body.appendChild(toast);
        }

        toast.textContent = String(message);
        toast.style.opacity = "1";

        clearTimeout(toast._hideTimer);

        toast._hideTimer = setTimeout(function () {
            toast.style.opacity = "0";
        }, 2200);
    };

    console.log("New Star Supabase connected successfully.");
})();
