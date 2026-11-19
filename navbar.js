
(() => {
  "use strict";

  if (window.__NEWSTAR_NAVBAR_LOADED__) return;
  window.__NEWSTAR_NAVBAR_LOADED__ = true;

  const db = window.supabaseClient || window.cloudClient;
  const currentPage =
    location.pathname.split("/").pop() || "index.html";

  const authPages = ["auth.html", "login.html", "signup.html"];

  const protectedPages = [
    "wallet.html",
    "inbox.html",
    "monetization.html",
    "live.html",
    "gaming.html",
    "camera.html",
    "upload.html",
    "settings.html",
    "admin.html",
    "creator-ai.html"
  ];

  const $ = id => document.getElementById(id);

  function getPrefs(){try{return JSON.parse(localStorage.getItem("newstar_preferences")||"{}");}catch(_){return {};}}
  function applyPreferences(){
    // preferences.js is the single source of truth for app-wide appearance.
    const preferences = window.NewStarPreferences;
    if (preferences && typeof preferences.apply === "function") {
      preferences.apply();
      return;
    }
    const p = getPrefs();
    const lang = p.language || "hi";
    document.documentElement.lang = lang;
    document.documentElement.dir = ["ar","ur","fa"].includes(lang) ? "rtl" : "ltr";
    window.NewStarI18n?.apply();
  }

  function navText(key){
    const lang=getPrefs().language||"hi";
    const words={
      Home:{hi:"होम",ne:"गृह",en:"Home",zh:"首页",es:"Inicio",fr:"Accueil",ar:"الرئيسية",ja:"ホーム",de:"Start",ru:"Главная",pt:"Início",it:"Home",ko:"홈",tr:"Ana Sayfa",bn:"হোম",ur:"صفحہ اول",id:"Beranda",vi:"Trang chủ",th:"หน้าหลัก",fa:"خانه",sw:"Nyumbani",nl:"Home",pl:"Start",uk:"Головна",el:"Αρχική",sv:"Hem"},
      Wallet:{hi:"वॉलेट",ne:"वालेट",en:"Wallet",zh:"钱包",es:"Billetera",fr:"Portefeuille",ar:"المحفظة",ja:"ウォレット",de:"Wallet",ru:"Кошелёк",pt:"Carteira",it:"Portafoglio",ko:"지갑",tr:"Cüzdan",bn:"ওয়ালেট",ur:"والٹ",id:"Dompet",vi:"Ví",th:"กระเป๋าเงิน",fa:"کیف پول",sw:"Pochi",nl:"Portemonnee",pl:"Portfel",uk:"Гаманець",el:"Πορτοφόλι",sv:"Plånbok"},
      Create:{hi:"बनाएँ",ne:"सिर्जना",en:"Create",zh:"创建",es:"Crear",fr:"Créer",ar:"إنشاء",ja:"作成",de:"Erstellen",ru:"Создать",pt:"Criar",it:"Crea",ko:"만들기",tr:"Oluştur",bn:"তৈরি",ur:"بنائیں",id:"Buat",vi:"Tạo",th:"สร้าง",fa:"ایجاد",sw:"Unda",nl:"Maken",pl:"Utwórz",uk:"Створити",el:"Δημιουργία",sv:"Skapa"},
      Inbox:{hi:"इनबॉक्स",ne:"इनबक्स",en:"Inbox",zh:"收件箱",es:"Bandeja",fr:"Boîte",ar:"الوارد",ja:"受信箱",de:"Postfach",ru:"Входящие",pt:"Caixa",it:"Posta",ko:"받은편지함",tr:"Gelen Kutusu",bn:"ইনবক্স",ur:"پیغامات",id:"Kotak Masuk",vi:"Hộp thư",th:"กล่องข้อความ",fa:"صندوق ورودی",sw:"Kikasha",nl:"Inbox",pl:"Skrzynka",uk:"Вхідні",el:"Εισερχόμενα",sv:"Inkorg"},
      Admin:{hi:"एडमिन",ne:"एडमिन",en:"Admin",zh:"管理",es:"Admin",fr:"Admin",ar:"المشرف",ja:"管理",de:"Admin",ru:"Админ",pt:"Admin",it:"Admin",ko:"관리자",tr:"Yönetici",bn:"অ্যাডমিন",ur:"منتظم",id:"Admin",vi:"Quản trị",th:"ผู้ดูแล",fa:"مدیر",sw:"Msimamizi",nl:"Beheer",pl:"Admin",uk:"Адмін",el:"Διαχειριστής",sv:"Admin"},
      Profile:{hi:"प्रोफाइल",ne:"प्रोफाइल",en:"Profile",zh:"个人资料",es:"Perfil",fr:"Profil",ar:"الملف الشخصي",ja:"プロフィール",de:"Profil",ru:"Профиль",pt:"Perfil",it:"Profilo",ko:"프로필",tr:"Profil",bn:"প্রোফাইল",ur:"پروفائل",id:"Profil",vi:"Hồ sơ",th:"โปรไฟล์",fa:"نمایه",sw:"Wasifu",nl:"Profiel",pl:"Profil",uk:"Профіль",el:"Προφίλ",sv:"Profil"}
    };
    return words[key]?.[lang]||words[key]?.en||key;
  }

  function addStyles() {
    if ($("newstar-navbar-style")) return;

    const style = document.createElement("style");
    style.id = "newstar-navbar-style";

    style.textContent = `
      #newstar-global-nav {
        position: fixed;
        left: 0;
        right: 0;
        bottom: 0;
        z-index: 9998;
        height: calc(64px + env(safe-area-inset-bottom));
        padding: 4px 5px env(safe-area-inset-bottom);
        background: #101010;
        border-top: 1px solid #292929;
        display: flex;
        align-items: center;
        justify-content: space-around;
        box-sizing: border-box;
        font-family: Arial, sans-serif;
      }

      #newstar-global-nav a {
        color: #aaa;
        text-decoration: none;
        display: flex;
        flex-direction: column;
        align-items: center;
        justify-content: center;
        gap: 4px;
        font-size: 11px;
        min-width: 0;
        flex: 1;
        height: 100%;
        padding: 5px 1px;
        box-sizing: border-box;
        -webkit-tap-highlight-color: transparent;
      }

      #newstar-global-nav a.active {
        color: #fe2c55;
      }

      #newstar-global-nav .nav-icon {
        font-size: 22px;
        line-height: 1;
      }

      #newstar-global-nav a.newstar-plus-link {
        flex: 1.1;
        min-width: 0;
      }

      #newstar-global-nav .newstar-plus-icon {
        width: 43px;
        height: 34px;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        background: linear-gradient(110deg,#fe2c55 0%,#fff 48%,#25f4ee 100%);
        color: #111;
        font-size: 30px;
        font-weight: 800;
        line-height: 1;
        border-radius: 10px;
        box-shadow: 0 2px 12px rgba(254,44,85,.25);
      }

      #newstar-global-nav a.newstar-plus-link.active
      .newstar-plus-icon {
        box-shadow: 0 0 0 2px #fe2c55;
      }

      #newstar-menu-button {
        border: 0;
        background: transparent;
        color: white;
        font-size: 27px;
        width: 38px;
        height: 38px;
        border-radius: 50%;
        cursor: pointer;
        display: inline-flex;
        align-items: center;
        justify-content: center;
        flex-shrink: 0;
      }

      #newstar-menu-button:active {
        background: #333;
      }

      #newstar-menu-overlay {
        position: fixed;
        inset: 0;
        z-index: 10000;
        background: rgba(0,0,0,.65);
        display: none;
      }

      #newstar-menu-overlay.open {
        display: block;
      }

      #newstar-menu-panel {
        position: absolute;
        right: 12px;
        top: 65px;
        width: min(290px,calc(100% - 24px));
        max-height: calc(100vh - 90px);
        overflow-y: auto;
        background: #191919;
        color: white;
        border: 1px solid #383838;
        border-radius: 14px;
        padding: 9px;
        box-shadow: 0 10px 35px rgba(0,0,0,.5);
      }

      #newstar-menu-panel a,
      #newstar-menu-panel button {
        width: 100%;
        border: 0;
        background: transparent;
        color: white;
        text-decoration: none;
        padding: 13px 12px;
        text-align: left;
        display: block;
        font-size: 15px;
        border-radius: 8px;
        cursor: pointer;
        box-sizing: border-box;
      }

      #newstar-menu-panel a:active,
      #newstar-menu-panel button:active {
        background: #333;
      }

      #newstar-menu-panel .menu-divider {
        height: 1px;
        background: #333;
        margin: 6px 0;
      }

      body.newstar-nav-active {
        padding-bottom: calc(75px + env(safe-area-inset-bottom)) !important;
      }

      @media(max-width:380px) {
        #newstar-global-nav a {
          font-size: 10px;
        }

        #newstar-global-nav .newstar-plus-icon {
          width: 39px;
          height: 33px;
        }
      }
    `;

    document.head.appendChild(style);
  }

  function removeDuplicateNavigation() {
    document.querySelectorAll(
      ".bottom-nav, .bottomNav, .app-global-bottom-nav, .master-tiktok-final-nav, #newstar-global-nav"
    ).forEach(element => element.remove());
  }

  function createMenu() {
    if ($("newstar-menu-overlay")) return;

    const overlay = document.createElement("div");
    overlay.id = "newstar-menu-overlay";

    overlay.innerHTML = `
      <div id="newstar-menu-panel">
        <a href="camera.html" data-i18n="page.camera">📷 Camera / Create / Upload</a>
        <a href="creator-ai.html">✨ New Star Creator Studio</a>
        <a href="profile.html" data-i18n="nav.profile">👤 My Profile</a>
        <a href="settings.html" data-i18n="settings.title">⚙️ Main Settings</a>
        <a href="admin.html" id="newstar-owner-dashboard-link" hidden>🛡️ Developer / Owner Dashboard</a>
        <a href="wallet.html" data-i18n="nav.wallet">💰 Wallet</a>
        <a href="inbox.html" data-i18n="nav.inbox">✉️ Inbox</a>
        <a href="monetization.html" data-i18n="page.monetization">💎 Monetization</a>
         <a href="live.html" data-i18n="page.live">🔴 Live</a>
        <a href="gaming.html" data-i18n="page.gaming">🎮 Gaming</a>
        <div class="menu-divider"></div>
        <button type="button" id="newstar-edit-profile">
          ✏️ <span data-i18n="action.edit">Edit Profile</span>
        </button>
        <button type="button" id="newstar-logout">
          🚪 <span data-i18n="action.logout">Log Out</span>
        </button>
      </div>
    `;

    overlay.addEventListener("click", event => {
      if (event.target === overlay) closeMenu();
    });

    document.body.appendChild(overlay);

    $("newstar-edit-profile").addEventListener("click", () => {
      closeMenu();

      if (
        currentPage === "profile.html" &&
        window.NewStarProfile &&
        typeof window.NewStarProfile.openEdit === "function"
      ) {
        window.NewStarProfile.openEdit();
      } else {
        location.href = "profile.html";
      }
    });

    $("newstar-logout").addEventListener("click", async () => {
      if (!db?.auth) {
        alert("Supabase connection नहीं मिला।");
        return;
      }

      if (!confirm("क्या आप Logout करना चाहते हैं?")) return;

      const button = $("newstar-logout");
      button.disabled = true;
      button.textContent = "Logging out...";

      try {
        const { error } = await db.auth.signOut();
        if (error) throw error;

        location.replace("auth.html");
      } catch (error) {
        alert("Logout Error: " + error.message);
        button.disabled = false;
        button.textContent = "🚪 Log Out";
      }
    });
  }

  function openMenu() {
    $("newstar-menu-overlay")?.classList.add("open");

    $("newstar-menu-button")?.setAttribute(
      "aria-expanded",
      "true"
    );

    // Profile page uses its own menu button.
    $("menuBtn")?.setAttribute("aria-expanded", "true");
  }

  function closeMenu() {
    $("newstar-menu-overlay")?.classList.remove("open");

    $("newstar-menu-button")?.setAttribute(
      "aria-expanded",
      "false"
    );

    $("menuBtn")?.setAttribute("aria-expanded", "false");
  }

  function createTopMenuButton() {
    // Profile has its own menu; Index keeps the video controls untouched
    // and does not show the global three-dot menu button.
    if (currentPage === "profile.html" || currentPage === "index.html" || currentPage === "settings.html") return;

    if ($("newstar-menu-button")) return;

    const button = document.createElement("button");
    button.id = "newstar-menu-button";
    button.type = "button";
    button.textContent = "⋮";
    button.setAttribute("aria-label", "Open menu");
    button.setAttribute("aria-expanded", "false");

    button.addEventListener("click", () => {
      const isOpen =
        $("newstar-menu-overlay")?.classList.contains("open");

      if (isOpen) closeMenu();
      else openMenu();
    });

    const header = document.querySelector("header");

    if (header) {
      header.appendChild(button);
    } else {
      button.style.position = "fixed";
      button.style.top = "10px";
      button.style.right = "12px";
      button.style.zIndex = "9999";
      document.body.appendChild(button);
    }
  }

  function createBottomNavigation() {
    if ($("newstar-global-nav")) return;

    const nav = document.createElement("nav");
    nav.id = "newstar-global-nav";
    nav.setAttribute("aria-label", "Main navigation");

    const items = [
      { page: "index.html", icon: "🏠", label: navText("Home") },
      { page: "wallet.html", icon: "💰", label: navText("Wallet") },
      { page: "camera.html", icon: "+", label: navText("Create"), plus: true },
      { page: "inbox.html", icon: "✉️", label: navText("Inbox") },
      { page: "profile.html", icon: "👤", label: navText("Profile") }
    ];

    items.forEach(item => {
      const link = document.createElement("a");
      link.href = item.page;

      if (item.plus) {
        link.className = "newstar-plus-link";
        link.setAttribute("aria-label", "Create or upload video");

        link.innerHTML = `
          <span class="newstar-plus-icon" aria-hidden="true">+</span>
          <span data-i18n="nav.${item.page==="index.html"?"home":item.page==="wallet.html"?"wallet":item.page==="camera.html"?"create":item.page==="inbox.html"?"inbox":item.page==="admin.html"?"admin":"profile"}">${item.label}</span>
        `;
      } else {
        link.innerHTML = `
          <span class="nav-icon">${item.icon}</span>
          <span>${item.label}</span>
        `;
      }

      if (currentPage === item.page) {
        link.classList.add("active");
        link.setAttribute("aria-current", "page");
      }

      nav.appendChild(link);
    });

    document.body.appendChild(nav);
    document.body.classList.add("newstar-nav-active");
  }

  async function start() {
    applyPreferences();
    if (authPages.includes(currentPage)) return;

    if (!db?.auth) {
      console.error("New Star: Supabase client नहीं मिला।");
      return;
    }

    try {
      const { data, error } = await db.auth.getSession();

      if (error) throw error;

      const user = data?.session?.user || null;

      if (!user && protectedPages.includes(currentPage)) {
        location.replace("auth.html");
        return;
      }

      applyPreferences();
      removeDuplicateNavigation();
      addStyles();
      createMenu();

      // Display the dashboard link only after the server verifies this session's owner role.
      if (user) {
        try {
          const response = await fetch("https://uzsusgoetrkujmofxcmb.supabase.co/functions/v1/admin-overview", {
            method: "POST",
            headers: {
              Authorization: "Bearer " + data.session.access_token,
              apikey: "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr",
              "Content-Type": "application/json"
            },
            body: JSON.stringify({ action: "check_owner_access" })
          });
          const result = await response.json();
          if (response.ok && result?.ok && result.is_owner) {
            const ownerLink = $("newstar-owner-dashboard-link");
            if (ownerLink) ownerLink.hidden = false;
          }
        } catch (ownerCheckError) {
          console.warn("New Star owner menu check unavailable:", ownerCheckError);
        }
      }

      // Profile page keeps its original profile menu.
      createTopMenuButton();

      createBottomNavigation();


    } catch (error) {
      console.error("New Star Navigation Error:", error);
    }
  }

  window.NewStarNavbar = {
    openMenu, closeMenu, applyPreferences,
    setLanguage(language){
      if (window.NewStarPreferences?.set) window.NewStarPreferences.set({language});
      else { const p=getPrefs(); p.language=language; localStorage.setItem("newstar_preferences",JSON.stringify(p)); applyPreferences(); }
    },
    setTheme(theme){
      if (window.NewStarPreferences?.set) window.NewStarPreferences.set({theme});
      else { const p=getPrefs(); p.theme=theme; localStorage.setItem("newstar_preferences",JSON.stringify(p)); applyPreferences(); }
    }
  };
  window.addEventListener("storage",event=>{if(event.key==="newstar_preferences")applyPreferences();});

  // Allow the profile's existing ⋮ button to open the global menu
  // only when explicitly requested by its own page code.
  window.NewStarNavbar.openGlobalMenu = openMenu;

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, {
      once: true
    });
  } else {
    start();
  }

})();
