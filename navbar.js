(() => {
  "use strict";

  const db = window.supabaseClient || window.cloudClient;
  const currentPage = location.pathname.split("/").pop() || "index.html";

  const protectedPages = [
    "wallet.html",
    "inbox.html",
    "monetization.html",
    "live.html",
    "gaming.html",
    "camera.html",
    "upload.html"
  ];

  const authPages = ["auth.html", "login.html", "signup.html"];

  // Remove duplicate navigation bars
  function removeOldNavigation() {
    document.querySelectorAll(
      ".bottom-nav, .bottomNav, .app-global-bottom-nav, .master-tiktok-final-nav, #newstar-global-nav"
    ).forEach(el => el.remove());
  }

  async function checkAuthentication() {
    if (!db || !db.auth) return null;

    try {
      const { data, error } = await db.auth.getSession();
      if (error) throw error;

      const user = data?.session?.user || null;

      if (!user && protectedPages.includes(currentPage)) {
        location.replace("auth.html");
        return null;
      }

      return user;
    } catch (error) {
      console.error("Authentication check:", error);
      return null;
    }
  }

  function addStyles() {
    if (document.getElementById("newstar-navbar-style")) return;

    const style = document.createElement("style");
    style.id = "newstar-navbar-style";
    style.textContent = `
      #newstar-global-nav {
        position: fixed;
        left: 0;
        right: 0;
        bottom: 0;
        z-index: 9998;
        height: 64px;
        padding-bottom: env(safe-area-inset-bottom);
        background: #101010;
        border-top: 1px solid #292929;
        display: flex;
        align-items: center;
        justify-content: space-around;
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
        min-width: 55px;
      }

      #newstar-global-nav a.active {
        color: #fff;
      }

      #newstar-global-nav .nav-icon {
        font-size: 22px;
        line-height: 1;
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
        width: min(280px, calc(100% - 24px));
        background: #191919;
        color: white;
        border: 1px solid #333;
        border-radius: 14px;
        padding: 10px;
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
      }

      #newstar-menu-panel a:active,
      #newstar-menu-panel button:active {
        background: #333;
      }

      #newstar-menu-panel .menu-divider {
        height: 1px;
        background: #333;
        margin: 5px 0;
      }

      body.newstar-nav-active {
        padding-bottom: 80px !important;
      }
    `;
    document.head.appendChild(style);
  }

  function createMenu() {
    if (document.getElementById("newstar-menu-overlay")) return;

    const overlay = document.createElement("div");
    overlay.id = "newstar-menu-overlay";

    overlay.innerHTML = `
      <div id="newstar-menu-panel">
        <a href="camera.html">📷 Camera / Create / Upload</a>
        <a href="settings.html">⚙️ Settings & Privacy</a>
        <a href="wallet.html">💰 Wallet</a>
        <a href="monetization.html">💎 Monetization</a>
        <a href="live.html">🔴 Live</a>
        <a href="gaming.html">🎮 Gaming</a>
        <div class="menu-divider"></div>
        <button type="button" id="newstar-logout">🚪 Log Out</button>
      </div>
    `;

    overlay.addEventListener("click", event => {
      if (event.target === overlay) overlay.classList.remove("open");
    });

    document.body.appendChild(overlay);

    document.getElementById("newstar-logout").addEventListener("click", async () => {
      if (!db?.auth) {
        alert("Supabase connection नहीं मिला।");
        return;
      }

      const confirmed = confirm("क्या आप Logout करना चाहते हैं?");
      if (!confirmed) return;

      const { error } = await db.auth.signOut();

      if (error) {
        alert("Logout नहीं हुआ: " + error.message);
        return;
      }

      location.replace("auth.html");
    });
  }

  function openMenu() {
    document.getElementById("newstar-menu-overlay")?.classList.add("open");
  }

  function createNavbar() {
    if (authPages.includes(currentPage)) return;

    removeOldNavigation();
    addStyles();
    createMenu();

    const nav = document.createElement("nav");
    nav.id = "newstar-global-nav";

    const items = [
      { page: "index.html", icon: "🏠", label: "Home" },
      { page: "wallet.html", icon: "💰", label: "Wallet" },
      { page: "inbox.html", icon: "✉️", label: "Inbox" },
      { page: "profile.html", icon: "👤", label: "Profile" }
    ];

    items.forEach(item => {
      const link = document.createElement("a");
      link.href = item.page;
      link.innerHTML = `
        <span class="nav-icon">${item.icon}</span>
        <span>${item.label}</span>
      `;

      if (currentPage === item.page) {
        link.classList.add("active");
      }

      nav.appendChild(link);
    });

    const menuButton = document.createElement("a");
    menuButton.href = "#";
    menuButton.innerHTML = `
      <span class="nav-icon">⋯</span>
      <span>More</span>
    `;

    menuButton.addEventListener("click", event => {
      event.preventDefault();
      openMenu();
    });

    nav.appendChild(menuButton);
    document.body.appendChild(nav);
    document.body.classList.add("newstar-nav-active");
  }

  async function start() {
    const user = await checkAuthentication();

    if (protectedPages.includes(currentPage) && !user) return;

    createNavbar();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start);
  } else {
    start();
  }

  window.NewStarNavbar = {
    openMenu,
    closeMenu: () => {
      document.getElementById("newstar-menu-overlay")?.classList.remove("open");
    }
  };
})();
