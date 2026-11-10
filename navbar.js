// navbar.js - Ultimate Master Navigation with Center Header Title & Clean Top Bar
document.addEventListener("DOMContentLoaded", function() {
    let currentPage = window.location.pathname.split("/").pop() || "index.html";

    // 1. पुराना नेविगेशन तुरंत हटाओ ताकि डुप्लीकेट न बने
    let oldNavs = document.querySelectorAll('.bottom-nav, .app-global-bottom-nav, .master-tiktok-final-nav, .top-left-live-wrapper');
    oldNavs.forEach(el => el.remove());

    // 2. ⚙️ ऊपर हेडर के टाइटल को बिल्कुल बीच में (Center) और सेटिंग गियर को दाहिने कोने में सेट करने का कोड (ऊपर का लाइव बटन पूरी तरह हटा दिया गया है)
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

    // 3. टिकटॉक जैसा नीचे का नेविगेशन बार (बिना किसी ऊपर वाले लाइव बटन के)
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
        
        /* ➕ बीच वाला चमकता हुआ प्लस बटन जो सीधा camera.html पर ले जाएगा */
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
    </style>

    <!-- नीचे का फिक्स नेविगेशन बार -->
    <div class="master-tiktok-final-nav">
        <a href="index.html" class="mfn-item ${currentPage === 'index.html' || currentPage === '' ? 'active' : ''}"><i>🏠</i><span>Home</span></a>
        <a href="wallet.html" class="mfn-item ${currentPage === 'wallet.html' ? 'active' : ''}"><i>🪙</i><span>Wallet</span></a>
        <a href="camera.html" class="mfn-item" style="flex: 1.5;"><div class="mfn-center-plus">+</div></a>
        <a href="inbox.html" class="mfn-item ${currentPage === 'inbox.html' ? 'active' : ''}"><i>💬</i><span>Inbox</span></a>
        <a href="profile.html" class="mfn-item ${currentPage === 'profile.html' ? 'active' : ''}"><i>⭐</i><span>Profile</span></a>
    </div>
    `;

    let wrap = document.createElement('div');
    wrap.innerHTML = finalNavHTML;
    document.body.appendChild(wrap);
});
