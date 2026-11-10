// navbar.js - Ultimate Master Navigation with Center Header Title & Smooth History Routing
document.addEventListener("DOMContentLoaded", function() {
    let currentPage = window.location.pathname.split("/").pop() || "index.html";

    // 1. पुराना नेविगेशन तुरंत हटाओ ताकि डुप्लीकेट न बने
    let oldNavs = document.querySelectorAll('.bottom-nav, .app-global-bottom-nav, .master-tiktok-final-nav');
    oldNavs.forEach(el => el.remove());

    // 2. ⚙️ ऊपर हेडर के टाइटल को बिल्कुल बीच में (Center) और सेटिंग गियर को दाहिने कोने में सेट करने का कोड
    function fixHeaderTitleAndGear() {
        let topBars = document.querySelectorAll('.top-bar');
        topBars.forEach(bar => {
            bar.style.cssText = "position: relative !important; display: flex !important; justify-content: space-between !important; align-items: center !important; width: 100% !important; max-width: 450px !important; margin: 10px auto !important; padding: 0 15px !important; box-sizing: border-box !important; min-height: 40px !important;";

            let titleEl = bar.querySelector('h3, h2, .profile-title');
            if (titleEl) {
                titleEl.style.cssText = "position: absolute !important; left: 50% !important; transform: translateX(-50%) !important; font-size: 16px !important; color: #fe2c55 !important; margin: 0 !important; text-align: center !important; font-weight: bold !important; width: max-content !important; pointer-events: none !important;";
            }

            if (!bar.querySelector('.top-settings-gear')) {
                let gearBtn = document.createElement('a');
                gearBtn.href = 'settings.html';
                gearBtn.className = 'top-settings-gear';
                gearBtn.innerHTML = '⚙️';
                gearBtn.title = 'Settings & Privacy';
                gearBtn.style.cssText = "font-size: 18px; text-decoration: none; color: #fff; background: rgba(0,0,0,0.6); border: 1px solid rgba(254,44,85,0.5); width: 34px; height: 34px; border-radius: 50%; display: flex; align-items: center; justify-content: center; cursor: pointer; margin-left: auto; z-index: 999999; position: relative;";
                
                gearBtn.onclick = function(e) {
                    e.preventDefault();
                    window.location.href = 'settings.html';
                };

                bar.appendChild(gearBtn);
            }
        });
    }

    fixHeaderTitleAndGear();
    setTimeout(fixHeaderTitleAndGear, 50);
    setTimeout(fixHeaderTitleAndGear, 200);
    setTimeout(fixHeaderTitleAndGear, 500);

    // 3. टिकटॉक जैसा नीचे का नेविगेशन बार (Smooth Transition Links के साथ)
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
        
        /* ➕ बीच वाला चमकता हुआ प्लस बटन */
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
        }

        /* 🔴 ऊपर बाएं कोने वाला LIVE बटन */
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
        }
        .top-popup-link:hover {
            background: #252525;
            color: #fe2c55;
        }
    </style>

    <!-- नीचे का फिक्स नेविगेशन बार -->
    <div class="master-tiktok-final-nav">
        <a href="index.html" class="mfn-item ${currentPage === 'index.html' || currentPage === '' ? 'active' : ''}"><i>🏠</i><span>Home</span></a>
        <a href="wallet.html" class="mfn-item ${currentPage === 'wallet.html' ? 'active' : ''}"><i>🪙</i><span>Wallet</span></a>
        <a href="camera.html" class="mfn-item" style="flex: 1.5;"><div class="mfn-center-plus">+</div></a>
        <a href="inbox.html" class="mfn-item ${currentPage === 'inbox.html' ? 'active' : ''}"><i>💬</i><span>Inbox</span></a>
        <a href="profile.html" class="mfn-item ${currentPage === 'profile.html' ? 'active' : ''}"><i>⭐</i><span>Profile</span></a>
    </div>

    <!-- 🔴 ऊपर बाएं कोने में 'LIVE' बटन -->
    <div class="top-left-live-wrapper">
        <button class="top-live-pill-btn" onclick="toggleTopLiveMenu(event)">
            <span style="width:7px; height:7px; background:#fe2c55; border-radius:50%; display:inline-block;"></span>
            LIVE
        </button>
        <div id="topLivePopupBox">
            <a href="live.html" class="top-popup-link"><span>🔴</span><div><strong>Live Stream</strong><br><span style="font-size:9px; color:#aaa;">Broadcast & Gifts</span></div></a>
            <div style="height:1px; background:#222; margin:2px 0;"></div>
            <a href="gaming.html" class="top-popup-link"><span>🎮</span><div><strong>Gaming Arena</strong><br><span style="font-size:9px; color:#aaa;">Free Fire & Tournaments</span></div></a>
        </div>
    </div>
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
