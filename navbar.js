
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
    "admin.html"
  ];

  const $ = id => document.getElementById(id);

  function getPrefs(){try{return JSON.parse(localStorage.getItem("newstar_preferences")||"{}");}catch(_){return {};}}
  function applyPreferences(){
    const p=getPrefs(),theme=p.theme||"dark",lang=p.language||"hi";
    document.documentElement.lang=lang;
    document.documentElement.dir=["ar","ur","fa"].includes(lang)?"rtl":"ltr";
    window.NewStarI18n?.apply();
    const themes={
      dark:{bg:"#080808",panel:"#171717",text:"#ffffff",muted:"#aaaaaa",accent:"#fe2c55"},light:{bg:"#f4f6fa",panel:"#ffffff",text:"#171923",muted:"#5d6472",accent:"#d92350"},
      ocean:{bg:"#071923",panel:"#102b3a",text:"#f4fbff",muted:"#a8c1cc",accent:"#25b8d0"},forest:{bg:"#101b14",panel:"#1b2b20",text:"#f4fff6",muted:"#afc5b3",accent:"#39a96b"},
      sunset:{bg:"#21130e",panel:"#3b2118",text:"#fff4ed",muted:"#d9a88d",accent:"#ff874d"},purple:{bg:"#160e28",panel:"#281943",text:"#f7f0ff",muted:"#c2addb",accent:"#b58cff"},
      rose:{bg:"#260f1b",panel:"#3b1b2b",text:"#fff1f6",muted:"#d9a9bc",accent:"#ff6f9e"},crimson:{bg:"#210b10",panel:"#39131d",text:"#fff1f3",muted:"#d7a1aa",accent:"#ff405d"},
      gold:{bg:"#211b0b",panel:"#382d12",text:"#fff8df",muted:"#d5c28a",accent:"#e9b949"},teal:{bg:"#08201e",panel:"#103532",text:"#eafffb",muted:"#a1cbc4",accent:"#26c6b5"},
      cyan:{bg:"#071c27",panel:"#103344",text:"#edfbff",muted:"#a6cbd8",accent:"#48d5f5"},indigo:{bg:"#10132c",panel:"#1b2250",text:"#f0f2ff",muted:"#aeb6df",accent:"#7d8cff"},
      lavender:{bg:"#1d1730",panel:"#30264a",text:"#f8f2ff",muted:"#c8b8df",accent:"#c2a0ff"},mint:{bg:"#0d211b",panel:"#18372c",text:"#edfff6",muted:"#add2bf",accent:"#62d6a2"},
      emerald:{bg:"#071d16",panel:"#103526",text:"#edfff6",muted:"#a3cbb7",accent:"#28c985"},ruby:{bg:"#220c1c",panel:"#3b1530",text:"#fff0fa",muted:"#d7a5c6",accent:"#f34e9b"},
      amber:{bg:"#211608",panel:"#3b2810",text:"#fff6e8",muted:"#d6bd91",accent:"#ffb02e"},coral:{bg:"#24120f",panel:"#3c211c",text:"#fff3ef",muted:"#d9aaa0",accent:"#ff806e"},
      slate:{bg:"#111820",panel:"#202b36",text:"#f3f6fa",muted:"#aebbc8",accent:"#8ba8c4"},coffee:{bg:"#1b120d",panel:"#302117",text:"#fff5ec",muted:"#c9ae98",accent:"#c58b5a"},
      plum:{bg:"#1e1021",panel:"#351d39",text:"#fff1ff",muted:"#c7a9cb",accent:"#c17acb"},sakura:{bg:"#27151d",panel:"#432632",text:"#fff3f7",muted:"#d9b2c0",accent:"#ff91b8"},
      neon:{bg:"#080d16",panel:"#121d2b",text:"#f4ffff",muted:"#a5c2c7",accent:"#4dffdb"},sand:{bg:"#211d14",panel:"#383124",text:"#fff9e9",muted:"#d1c4a4",accent:"#d8a85d"},
      aurora:{bg:"#0c1720",panel:"#192b38",text:"#f2ffff",muted:"#a8c7d0",accent:"#7ee0c3"}
    };
    const t=themes[theme]||themes.dark;
    const style=document.getElementById("newstar-preference-style")||document.createElement("style");
    style.id="newstar-preference-style";
    style.textContent=`
      :root{--ns-bg:${t.bg};--ns-panel:${t.panel};--ns-text:${t.text};--ns-muted:${t.muted};--ns-accent:${t.accent}}
      body{background-color:var(--ns-bg)!important;color:var(--ns-text)!important}
      header,.header,.panel,.card,.section,.settings-card,.setting-row,.modal-content,.menu,.intro,.row{color:var(--ns-text)}
      .panel,.card,.settings-card,.setting-row,.modal-content,.row{background-color:var(--ns-panel)!important;border-color:var(--ns-muted)!important}
      .intro{background:var(--ns-panel)!important;border-color:var(--ns-muted)!important}
      input,select,textarea{background-color:var(--ns-bg)!important;color:var(--ns-text)!important;border-color:var(--ns-muted)!important}
      .muted,.small-note,.setting-row p,.section-title{color:var(--ns-muted)!important}
      button:not(.newstar-plus-icon){accent-color:var(--ns-accent)}
      button:not(.danger):not(.newstar-plus-icon),.primary-button,.save-button{border-color:var(--ns-accent)!important}
      button:not(.danger):not(.secondary):not(.newstar-plus-icon),.primary-button,.save-button{background-color:var(--ns-accent)!important;color:#fff!important}
      #newstar-global-nav{background:var(--ns-panel)!important}
      #newstar-global-nav a.active{color:var(--ns-accent)!important}
      #newstar-menu-panel{background:var(--ns-panel)!important;color:var(--ns-text)!important}
      #newstar-menu-panel a,#newstar-menu-panel button{color:var(--ns-text)!important}
      `;
    if(!style.isConnected)document.head.appendChild(style);
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
        <a href="profile.html" data-i18n="nav.profile">👤 My Profile</a>
        <a href="settings.html" data-i18n="settings.title">⚙️ Main Settings</a>
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
    if (currentPage === "profile.html" || currentPage === "index.html") return;

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

      // Profile page keeps its original profile menu.
      createTopMenuButton();

      createBottomNavigation();

      if (!document.querySelector('script[data-newstar-ai-chat]')) {
        const aiScript = document.createElement("script");
        aiScript.src = "/ai-chat.js";
        aiScript.defer = true;
        aiScript.dataset.newstarAiChat = "true";
        document.body.appendChild(aiScript);
      }

    } catch (error) {
      console.error("New Star Navigation Error:", error);
    }
  }

  window.NewStarNavbar = {
    openMenu, closeMenu, applyPreferences,
    setLanguage(language){const p=getPrefs();p.language=language;localStorage.setItem("newstar_preferences",JSON.stringify(p));applyPreferences();window.NewStarPreferences?.sync?.(true);},
    setTheme(theme){const p=getPrefs();p.theme=theme;localStorage.setItem("newstar_preferences",JSON.stringify(p));applyPreferences();window.NewStarPreferences?.sync?.(true);}
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
