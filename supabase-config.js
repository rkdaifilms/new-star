// ⚡ Central Supabase Configuration for New Star Creator Hub
const SUPABASE_URL = "https://uzsusgoetrkujmofxcmb.supabase.co";
const SUPABASE_ANON_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr";

// Initialize Global Supabase Client
const { createClient } = window.supabase;
const supabaseClient = createClient(SUPABASE_URL, SUPABASE_ANON_KEY);
