
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
    "settings.html"
  ];

  const $ = id => document.getElementById(id);

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
        <a href="camera.html">📷 Camera / Create / Upload</a>
        <a href="profile.html">👤 My Profile</a>
        <a href="settings.html">⚙️ Settings & Privacy</a>
        <a href="wallet.html">💰 Wallet</a>
        <a href="inbox.html">✉️ Inbox</a>
        <a href="monetization.html">💎 Monetization</a>
        <a href="live.html">🔴 Live</a>
        <a href="gaming.html">🎮 Gaming</a>
        <div class="menu-divider"></div>
        <button type="button" id="newstar-edit-profile">
          ✏️ Edit Profile
        </button>
        <button type="button" id="newstar-logout">
          🚪 Log Out
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
    $("newstar-menu-button")?.setAttribute("aria-expanded", "true");
  }

  function closeMenu() {
    $("newstar-menu-overlay")?.classList.remove("open");
    $("newstar-menu-button")?.setAttribute("aria-expanded", "false");
  }

  function createTopMenuButton() {
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
      { page: "index.html", icon: "🏠", label: "Home" },
      { page: "wallet.html", icon: "💰", label: "Wallet" },
      { page: "camera.html", icon: "+", label: "Create", plus: true },
      { page: "inbox.html", icon: "✉️", label: "Inbox" },
      { page: "profile.html", icon: "👤", label: "Profile" }
    ];

    items.forEach(item => {
      const link = document.createElement("a");
      link.href = item.page;

      if (item.plus) {
        link.className = "newstar-plus-link";
        link.setAttribute("aria-label", "Create or upload video");

        link.innerHTML = `
          <span class="newstar-plus-icon" aria-hidden="true">+</span>
          <span>Create</span>
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

      removeDuplicateNavigation();
      addStyles();
      createMenu();
      createTopMenuButton();
      createBottomNavigation();

    } catch (error) {
      console.error("New Star Navigation Error:", error);
    }
  }

  window.NewStarNavbar = {
    openMenu,
    closeMenu
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", start, {
      once: true
    });
  } else {
    start();
  }

})();
