
// ==============================================
// NEW STAR - GLOBAL NAVIGATION + LOGIN PROTECTION
// ==============================================

(function () {
    "use strict";

    const currentPage =
        location.pathname.split("/").pop() || "index.html";

    const PUBLIC_PAGES = ["index.html", "auth.html"];

    const PROTECTED_PAGES = [
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

    const isPublicPage = PUBLIC_PAGES.includes(currentPage);

    let authCheckFinished = false;
    let authenticated = false;

    function getClient() {
        return window.supabaseClient || window.cloudClient || null;
    }

    function getAuthUrl() {
        const next =
            location.pathname.split("/").pop() +
            location.search +
            location.hash;

        return "auth.html?next=" + encodeURIComponent(next);
    }

    function goToLogin() {
        location.replace(getAuthUrl());
    }

    // Hide protected pages until authentication is checked.
    if (PROTECTED_PAGES.includes(currentPage)) {
        document.documentElement.style.visibility = "hidden";
    }

    // Central authentication check
    async function checkAuthentication() {
        const client = getClient();

        if (!client) {
            authCheckFinished = true;

            if (PROTECTED_PAGES.includes(currentPage)) {
                goToLogin();
            } else {
                console.error(
                    "New Star: Supabase client not found."
                );
            }
            return;
        }

        try {
            const { data, error } = await client.auth.getSession();

            if (error) throw error;

            authenticated = !!data.session;
            authCheckFinished = true;

            if (!authenticated &&
                PROTECTED_PAGES.includes(currentPage)) {
                goToLogin();
                return;
            }

            if (PROTECTED_PAGES.includes(currentPage)) {
                document.documentElement.style.visibility = "visible";
            }

        } catch (error) {
            console.error("New Star auth check failed:", error);

            authCheckFinished = true;

            if (PROTECTED_PAGES.includes(currentPage)) {
                goToLogin();
            } else {
                document.documentElement.style.visibility = "visible";
            }
        }
    }

    // Keep the login state updated after login/logout.
    const client = getClient();

    if (client) {
        client.auth.onAuthStateChange((event, session) => {
            authenticated = !!session;

            if (
                event === "SIGNED_OUT" &&
                PROTECTED_PAGES.includes(currentPage)
            ) {
                goToLogin();
            }
        });
    }

    // Catch navigation to protected pages.
    document.addEventListener("click", function (event) {
        const link = event.target.closest("a[href]");

        if (!link) return;

        const href = link.getAttribute("href");

        if (!href || href.startsWith("#") ||
            href.startsWith("javascript:") ||
            link.target === "_blank") {
            return;
        }

        const destination = href.split("?")[0].split("#")[0];
        const destinationPage = destination.split("/").pop();

        if (!PROTECTED_PAGES.includes(destinationPage)) return;

        if (!authCheckFinished || !authenticated) {
            event.preventDefault();
            event.stopImmediatePropagation();
            goToLogin();
        }
    }, true);

    // Home Feed: Guests can watch videos, but cannot interact.
    // Video playback, search and category browsing remain available.
    if (currentPage === "index.html") {
        document.addEventListener("click", function (event) {
            const protectedAction = event.target.closest(
                ".side-actions button, [data-auth-required]"
            );

            if (!protectedAction) return;

            if (!authCheckFinished || !authenticated) {
                event.preventDefault();
                event.stopImmediatePropagation();

                if (typeof window.showGlobalFastToast === "function") {
                    window.showGlobalFastToast(
                        "यह सुविधा इस्तेमाल करने के लिए Login करें।"
                    );
                }

                goToLogin();
            }
        }, true);
    }

    // Remove duplicate navigation bars.
    function removeOldNavigation() {
        document.querySelectorAll(
            ".bottom-nav, .bottomNav, " +
            ".app-global-bottom-nav, " +
            ".master-tiktok-final-nav, " +
            ".top-left-live-wrapper"
        ).forEach(element => element.remove());
    }

    // Global three-dot menu: PROFILE PAGE ONLY.
    function createProfileMenu() {
        if (currentPage !== "profile.html") return;
        if (document.getElementById("nsGlobalMenu")) return;

        const menu = document.createElement("div");
        menu.id = "nsGlobalMenu";

        menu.innerHTML = `
            <style>
                #nsMenuButton {
                    position:fixed;
                    top:12px;
                    right:14px;
                    z-index:10000001;
                    width:40px;
                    height:40px;
                    border-radius:50%;
                    background:#171717;
                    color:white;
                    border:1px solid #fe2c55;
                    font-size:25px;
                    cursor:pointer;
                }

                #nsMenuPanel {
                    display:none;
                    position:fixed;
                    top:60px;
                    right:12px;
                    width:min(290px,85vw);
                    max-height:75vh;
                    overflow:auto;
                    background:#17171d;
                    border:1px solid #444;
                    border-radius:14px;
                    z-index:10000002;
                    box-shadow:0 10px 35px #000;
                    padding:9px;
                }

                #nsMenuPanel.open {display:block}

                .ns-menu-link {
                    display:flex;
                    align-items:center;
                    gap:12px;
                    padding:13px 12px;
                    color:white;
                    text-decoration:none;
                    border-bottom:1px solid #303038;
                    font-size:14px;
                }

                .ns-menu-link:last-child {border:0}

                .ns-menu-heading {
                    color:#fe2c55;
                    font-weight:bold;
                    padding:10px;
                    border-bottom:1px solid #444;
                }

                .ns-menu-overlay {
                    display:none;
                    position:fixed;
                    inset:0;
                    background:#0008;
                    z-index:10000000;
                }

                .ns-menu-overlay.open {display:block}
            </style>

            <button id="nsMenuButton" aria-label="Profile settings menu">⋮</button>

            <div class="ns-menu-overlay" id="nsMenuOverlay"></div>

            <div id="nsMenuPanel">
                <div class="ns-menu-heading">⭐ PROFILE MENU</div>

                <a class="ns-menu-link" href="settings.html">
                    <span>⚙️</span>
                    <span>Profile Settings & Privacy</span>
                </a>

                <a class="ns-menu-link" href="wallet.html">
                    <span>🪙</span>
                    <span>Wallet & Balance</span>
                </a>

                <a class="ns-menu-link" href="monetization.html">
                    <span>💎</span>
                    <span>Monetization</span>
                </a>

                <a class="ns-menu-link" href="inbox.html">
                    <span>🔔</span>
                    <span>Notifications & Inbox</span>
                </a>

                <a class="ns-menu-link" href="settings.html#help">
                    <span>❓</span>
                    <span>Help & Support</span>
                </a>

                <a class="ns-menu-link" href="#" id="nsLogoutButton">
                    <span>🚪</span>
                    <span>Logout</span>
                </a>
            </div>
        `;

        document.body.appendChild(menu);

        const button = document.getElementById("nsMenuButton");
        const panel = document.getElementById("nsMenuPanel");
        const overlay = document.getElementById("nsMenuOverlay");

        function closeMenu() {
            panel.classList.remove("open");
            overlay.classList.remove("open");
        }

        button.addEventListener("click", function () {
            panel.classList.toggle("open");
            overlay.classList.toggle("open");
        });

        overlay.addEventListener("click", closeMenu);

        document.getElementById("nsLogoutButton")
            .addEventListener("click", async function (event) {
                event.preventDefault();

                const db = getClient();

                if (!db) {
                    location.href = "auth.html";
                    return;
                }

                const { error } = await db.auth.signOut();

                if (error) {
                    alert("Logout नहीं हो सका: " + error.message);
                    return;
                }

                location.replace("index.html");
            });
    }

    // Shared bottom navigation.
    // Auth page keeps its own existing navigation.
    function createBottomNavigation() {
        if (currentPage === "auth.html") return;

        if (document.querySelector(".master-tiktok-final-nav")) return;

        const nav = document.createElement("nav");
        nav.className = "master-tiktok-final-nav";

        nav.innerHTML = `
            <style>
                .master-tiktok-final-nav {
                    position:fixed;
                    bottom:0;
                    left:0;
                    width:100%;
                    height:62px;
                    background:#080808;
                    border-top:1px solid #292929;
                    display:flex;
                    justify-content:space-around;
                    align-items:center;
                    z-index:9999999;
                    padding-bottom:env(safe-area-inset-bottom);
                }

                .mfn-item {
                    flex:1;
                    display:flex;
                    flex-direction:column;
                    align-items:center;
                    justify-content:center;
                    text-decoration:none;
                    color:#888;
                    font-size:11px;
                    gap:3px;
                    height:100%;
                }

                .mfn-item.active {
                    color:white;
                    font-weight:bold;
                }

                .mfn-icon {font-size:21px}

                .mfn-plus {
                    width:45px;
                    height:32px;
                    border-radius:8px;
                    display:flex;
                    align-items:center;
                    justify-content:center;
                    background:linear-gradient(135deg,#25f4ee,#fe2c55);
                    color:white;
                    font-size:25px;
                    font-weight:bold;
                }
            </style>

            <a href="index.html"
               class="mfn-item ${currentPage === "index.html" ? "active" : ""}">
                <span class="mfn-icon">🏠</span>
                <span>Home</span>
            </a>

            <a href="wallet.html"
               class="mfn-item ${currentPage === "wallet.html" ? "active" : ""}">
                <span class="mfn-icon">🪙</span>
                <span>Wallet</span>
            </a>

            <a href="camera.html"
               class="mfn-item" aria-label="Create Video">
                <span class="mfn-plus">+</span>
            </a>

            <a href="inbox.html"
               class="mfn-item ${currentPage === "inbox.html" ? "active" : ""}">
                <span class="mfn-icon">💬</span>
                <span>Inbox</span>
            </a>

            <a href="profile.html"
               class="mfn-item ${currentPage === "profile.html" ? "active" : ""}">
                <span class="mfn-icon">👤</span>
                <span>Profile</span>
            </a>
        `;

        document.body.appendChild(nav);
    }

    // Initialize after HTML has loaded.
    document.addEventListener("DOMContentLoaded", function () {
        removeOldNavigation();

        if (currentPage !== "auth.html") {
            createBottomNavigation();
            createProfileMenu();
        }
    });

    // Start authentication check.
    if (document.readyState === "loading") {
        document.addEventListener(
            "DOMContentLoaded",
            checkAuthentication,
            { once: true }
        );
    } else {
        checkAuthentication();
    }

})();
