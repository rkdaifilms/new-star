// navbar.js - App Global Footer Navigation Component
document.addEventListener("DOMContentLoaded", function() {
    // Check current active page to highlight the right icon
    let currentPage = window.location.pathname.split("/").pop() || "index.html";

    let navHTML = `
    <style>
        .bottom-nav { position: fixed; bottom: 0; left: 0; width: 100%; height: 60px; background: #000; display: flex; justify-content: space-around; align-items: center; border-top: 1px solid #222; z-index: 1000; }
        .nav-item { display: flex; flex-direction: column; align-items: center; color: rgba(255,255,255,0.6); cursor: pointer; text-decoration: none; font-size: 11px; flex: 1; text-align: center; }
        .nav-item.active { color: #fff; }
        
        /* ➕ TikTok Center Glowing Plus Upload Button */
        .upload-plus-btn { width: 44px; height: 30px; background: linear-gradient(135deg, #25f4ee, #fe2c55); border-radius: 8px; display: flex; justify-content: center; align-items: center; color: #fff; font-size: 22px; font-weight: bold; box-shadow: 0 2px 10px rgba(254,44,85,0.5); margin: 0 auto; transition: 0.2s; }
        .upload-plus-btn:hover { transform: scale(1.08); }

        /* 🔴 LIVE & Gaming Popup Menu Modal */
        #agnLivePopupModal {
            display: none;
            position: fixed;
            bottom: 70px;
            left: 15px;
            background: #181818;
            border: 1px solid #333;
            border-radius: 14px;
            width: 200px;
            padding: 10px;
            z-index: 100000;
            box-shadow: 0 10px 25px rgba(0,0,0,0.9);
        }
        .agn-popup-option {
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
        .agn-popup-option:hover {
            background: #252525;
            color: #fe2c55;
        }
    </style>

    <div class="bottom-nav">
        <a href="index.html" class="nav-item ${currentPage === 'index.html' ? 'active' : ''}"><i>🏠</i><span>Home</span></a>
        <a href="wallet.html" class="nav-item ${currentPage === 'wallet.html' ? 'active' : ''}"><i>🪙</i><span>Wallet</span></a>
        
        <!-- 🔴 LIVE & Gaming Combined Menu Trigger on Left side -->
        <div class="nav-item" onclick="toggleAgnLivePopup(event)">
            <i style="color:#fe2c55; font-size:18px;">🔴🎮</i>
            <span style="color:#fe2c55; font-weight:bold;">LIVE & Play</span>
        </div>

        <!-- ➕ PERFECT CENTER PLUS BUTTON FOR VIDEO UPLOAD -->
        <a href="camera.html" class="nav-item" style="flex: 1.2;">
            <div class="upload-plus-btn">+</div>
        </a>

        <a href="inbox.html" class="nav-item ${currentPage === 'inbox.html' ? 'active' : ''}"><i>💬</i><span>Inbox</span></a>
        <a href="profile.html" class="nav-item ${currentPage === 'profile.html' ? 'active' : ''}"><i>⭐</i><span>Profile</span></a>
    </div>

    <!-- Popup Menu for LIVE & Gaming -->
    <div id="agnLivePopupModal">
        <a href="live.html" class="agn-popup-option">
            <span style="font-size:18px;">🔴</span>
            <div>
                <strong style="display:block; color:#fe2c55;">Live Stream</strong>
                <span style="font-size:10px; color:#aaa;">Broadcast & Send Gifts</span>
            </div>
        </a>
        <div style="height:1px; background:#222; margin:4px 0;"></div>
        <a href="gaming.html" class="agn-popup-option">
            <span style="font-size:18px;">🎮</span>
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

    window.toggleAgnLivePopup = function(event) {
        event.stopPropagation();
        let modal = document.getElementById('agnLivePopupModal');
        modal.style.display = (modal.style.display === 'block') ? 'none' : 'block';
    }

    // Close popup when clicking outside
    window.addEventListener('click', function() {
        let modal = document.getElementById('agnLivePopupModal');
        if (modal) modal.style.display = 'none';
    });
});
