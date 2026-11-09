// navbar.js - Ultimate Master Navigation Component (Top-Right Plus Permanently Destroyed)
document.addEventListener("DOMContentLoaded", function() {
    let currentPage = window.location.pathname.split("/").pop() || "index.html";

    // 1. पुराना या डबल नेविगेशन तुरंत हटाओ
    let oldNavs = document.querySelectorAll('.bottom-nav, .app-global-bottom-nav, .master-tiktok-final-nav');
    oldNavs.forEach(el => el.remove());

    // 2. 🛑 जादुई कोड: ऊपर दाहिने कोने या टॉप-बार में कहीं भी फालतू प्लस (+) बटन या कैमरा लिंक दिखे, उसे तुरंत जड़ से उड़ा दो!
    function removeAllTopRightPluses() {
        // टॉप बार या स्क्रीन के ऊपर वाले हिस्से के सभी लिंक्स और बटंस की जाँच करें
        let topElements = document.querySelectorAll('.top-bar a, .top-bar button, header a, header button');
        topElements.forEach(el => {
            let text = el.innerText || el.textContent || '';
            let href = el.getAttribute('href') || '';
            let title = el.getAttribute('title') || '';
            
            if (text.includes('➕') || text.includes('+') || href.includes('camera') || title.includes('Studio')) {
                el.remove();
            }
        });

        // इसके अलावा अगर पूरी स्क्रीन पर कोई भी ऐसा एलिमेंट है जो ऊपर दाहिने कोने में है और प्लस दिखाता है
        let allItems = document.querySelectorAll('a, button, div, span');
        allItems.forEach(el => {
            let text = el.innerText || el.textContent || '';
            let href = el.getAttribute('href') || '';
            if ((text.trim() === '➕' || text.trim() === '+' || href.includes('camera.html')) && !el.classList.contains('mfn-center-plus')) {
                let rect = el.getBoundingClientRect();
                // यदि यह स्क्रीन के ऊपर (टॉप 80 पिक्सेल के अंदर) और दाईं तरफ है
                if (rect.top < 80 && rect.left > window.innerWidth / 2) {
                    el.remove();
                }
            }
        });
    }

    removeAllTopRightPluses();
    setTimeout(removeAllTopRightPluses, 50);
    setTimeout(removeAllTopRightPluses, 250);

    // 3. नया साफ-सुथरा टिकटॉक जैसा नेविगेशन बार
    let finalNavHTML = `
    <style>
        .master-tiktok-final-nav {
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
            z-index: 9999999 !important;
            box-sizing: border-box !important;
            margin: 0 !important;
            padding: 0 !important;
        }
        .mfn-item {
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
        .mfn-item.active {
            color: #fff !important;
            font-weight: bold !important;
        }
        .mfn-item i {
            font-size: 18px !important;
            margin-bottom: 2px !important;
            font-style: normal !important;
        }
        
        /* ➕ एकदम सटीक बीच वाला चमकता हुआ प्लस अपलोड बटन */
        .mfn-center-plus {
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
        .mfn-center-plus:hover {
            transform: scale(1.08);
        }

        /* 🔴 ऊपर बाएं कोने वाला LIVE बटन और उसका पॉपअप मेनू */
        .top-left-live-wrapper {
            position: fixed !important;
            top: 10px !important;
            left: 15px !important;
            z-index: 10000000 !important;
        }
        .top-live-pill-btn {
            background: rgba(0, 0, 0, 0.7);
            border: 1px solid #fe2c55;
            color: #fe2c55;
            padding: 5px 12px;
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
        #topLivePopupBox {
            display: none;
            position: fixed;
            top: 45px;
            left: 15px;
            background: #181818;
            border: 1px solid #333;
            border-radius: 12px;
            width: 180px;
            padding: 8px;
            z-index: 10000001;
            box-shadow: 0 10px 25px rgba(0,0,0,0.95);
        }
        .top-popup-link {
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
        .top-popup-link:hover {
            background: #252525;
            color: #fe2c55;
        }
    </style>

    <!-- नीचे का फिक्स नेविगेशन बार (Wallet टैग यहाँ सही से बंद किया गया है) -->
    <div class="master-tiktok-final-nav">
        <a href="index.html" class="mfn-item ${currentPage === 'index.html' || currentPage === '' ? 'active' : ''}"><i>🏠</i><span>Home</span></a>
        <a href="wallet.html" class="mfn-item ${currentPage === 'wallet.html' ? 'active' : ''}"><i>🪙</i><span>Wallet</span></a>
        
        <!-- ➕ एकदम बीचों-बीच प्लस बटन -->
        <a href="camera.html" class="mfn-item" style="flex: 1.5;">
            <div class="mfn-center-plus">+</div>
        </a>

        <a href="inbox.html" class="mfn-item ${currentPage === 'inbox.html' ? 'active' : ''}"><i>💬</i><span>Inbox</span></a>
        <a href="profile.html" class="mfn-item ${currentPage === 'profile.html' ? 'active' : ''}"><i>⭐</i><span>Profile</span></a>
    </div>

    <!-- 🔴 ऊपर बाएं कोने में 'LIVE' बटन -->
    <div class="top-left-live-wrapper">
        <button class="top-live-pill-btn" onclick="toggleTopLiveMenu(event)">
            <span style="width:7px; height:7px; background:#fe2c55; border-radius:50%; display:inline-block; animation: blinkLive 1s infinite;"></span>
            LIVE
        </button>
        <div id="topLivePopupBox">
            <a href="live.html" class="top-popup-link">
                <span style="font-size:16px;">🔴</span>
                <div>
                    <strong style="display:block; color:#fe2c55;">Live Stream</strong>
                    <span style="font-size:9px; color:#aaa;">Broadcast & Gifts</span>
                </div>
            </a>
            <div style="height:1px; background:#222; margin:2px 0;"></div>
            <a href="gaming.html" class="top-popup-link">
                <span style="font-size:16px;">🎮</span>
                <div>
                    <strong style="display:block; color:#00b4d8;">Gaming Arena</strong>
                    <span style="font-size:9px; color:#aaa;">Free Fire & Tournaments</span>
                </div>
            </a>
        </div>
    </div>

    <style>
        @keyframes blinkLive { 0% { opacity: 1; } 50% { opacity: 0.3; } 100% { opacity: 1; } }
    </style>
    `;

    let wrap = document.createElement('div');
    wrap.innerHTML = finalNavHTML;
    document.body.appendChild(wrap);

    window.toggleTopLiveMenu = function(event) {
        event.stopPropagation();
        let box = document.getElementById('topLivePopupBox');
        box.style.display = (box.style.display === 'block') ? 'none' : 'block';
    }

    window.addEventListener('click', function() {
        let box = document.getElementById('topLivePopupBox');
        if (box) box.style.display = 'none';
    });
});
