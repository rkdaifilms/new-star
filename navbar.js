// navbar.js - App Global Footer Navigation Component (Master Fixed)
document.addEventListener("DOMContentLoaded", function() {
    // Check current active page to highlight the right icon
    let currentPage = window.location.pathname.split("/").pop() || "index.html";

    // Remove any existing navbars to avoid duplication clash
    let oldNavs = document.querySelectorAll('.bottom-nav, .app-global-bottom-nav, .master-tiktok-nav');
    oldNavs.forEach(el => el.remove());

    let navHTML = `
    <style>
        .master-tiktok-nav {
            position: fixed !important;
            bottom: 0 !important;
            left: 0 !important;
            width: 100% !important;
            height: 60px !important;
            background: #000 !important;
            display: flex !important;
            justify-content: space-around !important;
            align-items: center !important;
            border-top: 1px solid #222 !important;
            z-index: 999999 !important;
            box-sizing: border-box !important;
            margin: 0 !important;
            padding: 0 !important;
        }
        .mtn-item {
            display: flex !important;
            flex-direction: column !important;
            align-items: center !important;
            justify-content: center !important;
            color: rgba(255,255,255,0.5) !important;
            cursor: pointer !important;
            text-decoration: none !important;
            font-size: 11px !important;
            flex: 1 !important;
            text-align: center !important;
            background: transparent !important;
            border: none !important;
            transition: 0.2s;
        }
        .mtn-item.active {
            color: #fff !important;
            font-weight: bold !important;
        }
        .mtn-item i {
            font-size: 18px !important;
            margin-bottom: 2px !important;
            font-style: normal !important;
        }
        
        /* ➕ TikTok Center Glowing Plus Upload Button (EXACT CENTER) */
        .mtn-center-plus {
            width: 46px !important;
            height: 32px !important;
            background: linear-gradient(135deg, #25f4ee, #fe2c55) !important;
            border-radius: 8px !important;
            display: flex !important;
            justify-content: center !important;
            align-items: center !important;
            color: #fff !important;
            font-size: 24px !important;
            font-weight: bold !important;
            box-shadow: 0 2px 12px rgba(254,44,85,0.7) !important;
            margin: 0 auto !important;
            transition: 0.2s;
        }
        .mtn-center-plus:hover {
            transform: scale(1.08);
        }

        /* 🔴 LIVE & Gaming Popup Menu Modal on Left Corner */
        #mtnLivePopupModal {
            display: none;
            position: fixed;
            bottom: 70px;
            left: 15px;
            background: #181818;
            border: 1px solid #333;
            border-radius: 14px;
            width: 210px;
            padding: 10px;
            z-index: 1000000;
            box-shadow: 0 10px 25px rgba(0,0,0,0.95);
        }
        .mtn-popup-option {
            display: flex;
            align-items: center;
            gap: 10px;
            padding: 12px;
            color: #fff;
            text-decoration: none;
            font-size: 13px;
            border-radius: 8px;
            transition: 0.2s;
        }
        .mtn-popup-option:hover {
            background: #252525;
            color: #fe2c55;
        }
    </style>

    <div class="master-tiktok-nav">
        <a href="index.html" class="mtn-item ${currentPage === 'index.html' ? 'active' : ''}"><i>🏠</i><span>Home</span></a>
        <a href="wallet.html" class="mtn-item ${currentPage === 'wallet.html' ? 'active' : ''}"><i>🪙</i><span>Wallet</span></a>
        
        <!-- 🔴 LIVE & Gaming Combined Menu Trigger on Left side -->
        <div class="mtn-item" onclick="toggleMtnLivePopup(event)">
            <i style="color:#fe2c55; font-size:18px;">🔴🎮</i>
            <span style="color:#fe2c55; font-weight:bold;">LIVE & Play</span>
        </div>

        <!-- ➕ PERFECT CENTER PLUS BUTTON FOR VIDEO UPLOAD -->
        <a href="camera.html" class="mtn-item" style="flex: 1.2;">
            <div class="mtn-center-plus">+</div>
        </a>

        <a href="inbox.html" class="mtn-item ${currentPage === 'inbox.html' ? 'active' : ''}"><i>💬</i><span>Inbox</span></a>
        <a href="profile.html" class="mtn-item ${currentPage === 'profile.html' ? 'active' : ''}"><i>⭐</i><span>Profile</span></a>
    </div>

    <!-- Popup Menu for LIVE & Gaming -->
    <div id="mtnLivePopupModal">
        <a href="live.html" class="mtn-popup-option">
            <span style="font-size:20px;">🔴</span>
            <div>
                <strong style="display:block; color:#fe2c55;">Live Stream</strong>
                <span style="font-size:10px; color:#aaa;">Broadcast & Send Gifts</span>
            </div>
        </a>
        <div style="height:1px; background:#222; margin:4px 0;"></div>
        <a href="gaming.html" class="mtn-popup-option">
            <span style="font-size:20px;">🎮</span>
            <div>
                <strong style="display:block; color:#00b4d8;">Gaming Arena</strong>
                <span style="font-size:10px; color:#aaa;">Free Fire & Tournaments</span>
            </div>
        </a>
    </div>`;

    // Automatically inject footer into body
    let footerContainer = document.createElement('div');
    footerContainer.innerHTML = navHTML;
    document.body.appendChild(footerContainer);

    window.toggleMtnLivePopup = function(event) {
        event.stopPropagation();
        let modal = document.getElementById('mtnLivePopupModal');
        modal.style.display = (modal.style.display === 'block') ? 'none' : 'block';
    }

    // Close popup when clicking outside
    window.addEventListener('click', function() {
        let modal = document.getElementById('mtnLivePopupModal');
        if (modal) modal.style.display = 'none';
    });
});
