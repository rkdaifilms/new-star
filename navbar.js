// navbar.js - Ultimate Master Navigation Component (Old Navigation Killer)
document.addEventListener("DOMContentLoaded", function() {
    let currentPage = window.location.pathname.split("/").pop() || "index.html";

    // 🛑 पुराना कोई भी नेविगेशन, फुटर या मेनू मिले तो उसे तुरंत हमेशा के लिए डिलीट कर दो!
    function removeOldNavigations() {
        let unwantedSelectors = [
            '.bottom-nav', 
            '.app-global-bottom-nav', 
            '.master-tiktok-nav', 
            '.fixed-tiktok-nav',
            'div[style*="position: fixed"][style*="bottom: 0"]'
        ];
        
        unwantedSelectors.forEach(selector => {
            document.querySelectorAll(selector).forEach(el => {
                // हमारे नए वाले को छोड़कर बाकी सबको उड़ा दो
                if (!el.classList.contains('master-tiktok-bottom-nav')) {
                    el.remove();
                }
            });
        });
    }

    // तुरंत हटाओ और एक बार फिर से चेक करके हटाओ
    removeOldNavigations();
    setTimeout(removeOldNavigations, 50);
    setTimeout(removeOldNavigations, 200);

    // 🎨 नया और एकदम साफ टिकटॉक जैसा नेविगेशन बार
    let bottomNavHTML = `
    <style>
        .master-tiktok-bottom-nav {
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
        
        /* ➕ एकदम सटीक बीच वाला चमकता हुआ प्लस अपलोड बटन */
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

        /* 🔴 ऊपर बाईं तरफ कोने वाला LIVE बटन और उसका पॉपअप मेनू */
        .top-left-live-container {
            position: fixed !important;
            top: 10px !important;
            left: 15px !important;
            z-index: 1000000 !important;
        }
        .top-live-btn {
            background: rgba(0, 0, 0, 0.6);
            border: 1px solid #fe2c55;
            color: #fe2c55;
            padding: 6px 12px;
            border-radius: 20px;
            font-size: 12px;
            font-weight: bold;
            display: flex;
            align-items: center;
            gap: 6px;
            cursor: pointer;
            backdrop-filter: blur(5px);
            box-shadow: 0 2px 8px rgba(254,44,85,0.4);
        }
        #topLivePopupModal {
            display: none;
            position: fixed;
            top: 50px;
            left: 15px;
            background: #181818;
            border: 1px solid #333;
            border-radius: 12px;
            width: 180px;
            padding: 8px;
            z-index: 1000001;
            box-shadow: 0 10px 25px rgba(0,0,0,0.95);
        }
        .top-popup-option {
            display: flex;
            align-items: center;
            gap: 8px;
            padding: 10px;
            color: #fff;
            text-decoration: none;
            font-size: 12px;
            border-radius: 6px;
            transition: 0.2s;
        }
        .top-popup-option:hover {
            background: #252525;
            color: #fe2c55;
        }
    </style>

    <!-- नीचे का साफ-सुथरा नेविगेशन बार -->
    <div class="master-tiktok-bottom-nav">
        <a href="index.html" class="mtn-item ${currentPage === 'index.html' ? 'active' : ''}"><i>🏠</i><span>Home</span></a>
        <a href="wallet.html" class="mtn-item ${currentPage === 'wallet.html' ? 'active' : ''}"><i>🪙</i><span>Wallet</span></a>
        
        <!-- ➕ एकदम बीचों-बीच प्लस बटन -->
        <a href="camera.html" class="mtn-item" style="flex: 1.5;">
            <div class="mtn-center-plus">+</div>
        </a>

        <a href="inbox.html" class="mtn-item ${currentPage === 'inbox.html' ? 'active' : ''}"><i>💬</i><span>Inbox</span></a>
        <a href="profile.html" class="mtn-item ${currentPage === 'profile.html' ? 'active' : ''}"><i>⭐</i><span>Profile</span></a>
    </div>

    <!-- 🔴 ऊपर बाईं तरफ कोने में 'LIVE' बटन -->
    <div class="top-left-live-container">
        <button class="top-live-btn" onclick="toggleTopLivePopup(event)">
            <span style="width:8px; height:8px; background:#fe2c55; border-radius:50%; display:inline-block; animation: blink 1s infinite;"></span>
            LIVE
        </button>
        <div id="topLivePopupModal">
            <a href="live.html" class="top-popup-option">
                <span style="font-size:16px;">🔴</span>
                <div>
                    <strong style="display:block; color:#fe2c55;">Live Stream</strong>
                    <span style="font-size:9px; color:#aaa;">Broadcast & Gifts</span>
                </div>
            </a>
            <div style="height:1px; background:#222; margin:2px 0;"></div>
            <a href="gaming.html" class="top-popup-option">
                <span style="font-size:16px;">🎮</span>
                <div>
                    <strong style="display:block; color:#00b4d8;">Gaming Arena</strong>
                    <span style="font-size:9px; color:#aaa;">Free Fire & Tournaments</span>
                </div>
            </a>
        </div>
    </div>

    <style>
        @keyframes blink { 0% { opacity: 1; } 50% { opacity: 0.3; } 100% { opacity: 1; } }
    </style>
    `;

    // पेज पर नया बार जोड़ें
    let container = document.createElement('div');
    container.innerHTML = bottomNavHTML;
    document.body.appendChild(container);

    // ऊपर वाले LIVE पॉपअप को खोलने/बंद करने का फंक्शन
    window.toggleTopLivePopup = function(event) {
        event.stopPropagation();
        let modal = document.getElementById('topLivePopupModal');
        modal.style.display = (modal.style.display === 'block') ? 'none' : 'block';
    }

    // कहीं बाहर क्लिक करने पर पॉपअप बंद हो जाए
    window.addEventListener('click', function() {
        let modal = document.getElementById('topLivePopupModal');
        if (modal) modal.style.display = 'none';
    });
});
