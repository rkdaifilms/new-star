
/* NEW STAR - GLOBAL NAVIGATION SYSTEM */
document.addEventListener("DOMContentLoaded", function () {
    const currentPage = location.pathname.split("/").pop() || "index.html";

    // Remove duplicate navigation bars
    document.querySelectorAll(
        ".bottom-nav, .bottomNav, .app-global-bottom-nav, .master-tiktok-final-nav, .top-left-live-wrapper"
    ).forEach(el => el.remove());

    // Global menu links
    const menuItems = [
        ["👤", "My Profile", "profile.html"],
        ["🪙", "Wallet & Balance", "wallet.html"],
        ["💎", "Monetization", "monetization.html"],
        ["⚙️", "Profile Settings", "profile.html"],
        ["🔐", "Privacy & Security", "settings.html"],
        ["🔔", "Notifications", "inbox.html"],
        ["🛠️", "Main Settings", "settings.html"],
        ["❓", "Help & Support", "settings.html"]
    ];

    // Add global top menu
    if (!document.getElementById("nsGlobalMenu")) {
        const menu = document.createElement("div");
        menu.id = "nsGlobalMenu";

        menu.innerHTML = `
            <style>
                #nsMenuButton {
                    position:fixed;top:12px;right:14px;z-index:10000001;
                    width:40px;height:40px;border-radius:50%;
                    background:#171717;color:white;border:1px solid #fe2c55;
                    font-size:25px;cursor:pointer;
                }
                #nsMenuPanel {
                    display:none;position:fixed;top:60px;right:12px;
                    width:min(290px,85vw);max-height:75vh;overflow:auto;
                    background:#17171d;border:1px solid #444;border-radius:14px;
                    z-index:10000002;box-shadow:0 10px 35px #000;
                    padding:9px;
                }
                #nsMenuPanel.open {display:block}
                .ns-menu-link {
                    display:flex;align-items:center;gap:12px;
                    padding:13px 12px;color:white;text-decoration:none;
                    border-bottom:1px solid #303038;font-size:14px;
                }
                .ns-menu-link:last-child {border:0}
                .ns-menu-link:active {background:#333}
                .ns-menu-heading {
                    color:#fe2c55;font-weight:bold;padding:10px;
                    border-bottom:1px solid #444;
                }
                .ns-menu-overlay {
                    display:none;position:fixed;inset:0;
                    background:#0008;z-index:10000000;
                }
                .ns-menu-overlay.open {display:block}
            </style>

            <button id="nsMenuButton" aria-label="Open menu">⋮</button>
            <div class="ns-menu-overlay" id="nsMenuOverlay"></div>
            <div id="nsMenuPanel">
                <div class="ns-menu-heading">⭐ NEW STAR MENU</div>
                ${menuItems.map(item => `
                    <a class="ns-menu-link" href="${item[2]}">
                        <span>${item[0]}</span><span>${item[1]}</span>
                    </a>
                `).join("")}
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

        button.addEventListener("click", () => {
            panel.classList.toggle("open");
            overlay.classList.toggle("open");
        });

        overlay.addEventListener("click", closeMenu);
    }

    // Global bottom navigation
    const nav = document.createElement("nav");
    nav.className = "master-tiktok-final-nav";

    nav.innerHTML = `
        <style>
            .master-tiktok-final-nav {
                position:fixed;bottom:0;left:0;width:100%;height:62px;
                background:#080808;border-top:1px solid #292929;
                display:flex;justify-content:space-around;align-items:center;
                z-index:9999999;padding-bottom:env(safe-area-inset-bottom);
            }
            .mfn-item {
                flex:1;display:flex;flex-direction:column;
                align-items:center;justify-content:center;
                text-decoration:none;color:#888;font-size:11px;
                gap:3px;height:100%;
            }
            .mfn-item.active {color:white;font-weight:bold}
            .mfn-item .mfn-icon {font-size:21px}
            .mfn-plus {
                width:45px;height:32px;border-radius:8px;
                display:flex;align-items:center;justify-content:center;
                background:linear-gradient(135deg,#25f4ee,#fe2c55);
                color:white;font-size:25px;font-weight:bold;
            }
        </style>

        <a href="index.html" class="mfn-item ${currentPage === "index.html" ? "active" : ""}">
            <span class="mfn-icon">🏠</span><span>Home</span>
        </a>

        <a href="wallet.html" class="mfn-item ${currentPage === "wallet.html" ? "active" : ""}">
            <span class="mfn-icon">🪙</span><span>Wallet</span>
        </a>

        <a href="camera.html" class="mfn-item" aria-label="Create Video">
            <span class="mfn-plus">+</span>
        </a>

        <a href="inbox.html" class="mfn-item ${currentPage === "inbox.html" ? "active" : ""}">
            <span class="mfn-icon">💬</span><span>Inbox</span>
        </a>

        <a href="profile.html" class="mfn-item ${currentPage === "profile.html" ? "active" : ""}">
            <span class="mfn-icon">👤</span><span>Profile</span>
        </a>
    `;

    document.body.appendChild(nav);
});
