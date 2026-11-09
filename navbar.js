// navbar.js - App Global Footer Navigation Component
document.addEventListener("DOMContentLoaded", function() {
    // Check current active page to highlight the right icon
    let currentPage = window.location.pathname.split("/").pop() || "index.html";

    let navHTML = `
    <style>
        .bottom-nav { position: fixed; bottom: 0; left: 0; width: 100%; height: 60px; background: #000; display: flex; justify-content: space-around; align-items: center; border-top: 1px solid #222; z-index: 1000; }
        .nav-item { display: flex; flex-direction: column; align-items: center; color: rgba(255,255,255,0.6); cursor: pointer; text-decoration: none; font-size: 11px; flex: 1; text-align: center; }
        .nav-item.active { color: #fff; }
        .upload-plus-btn { width: 44px; height: 30px; background: linear-gradient(135deg, #25f4ee, #fe2c55); border-radius: 8px; display: flex; justify-content: center; align-items: center; color: #fff; font-size: 22px; font-weight: bold; box-shadow: 0 2px 10px rgba(254,44,85,0.5); margin: 0 auto; }
        .live-btn-nav { color: #fe2c55; font-weight: bold; }
        .game-btn-nav { color: #00b4d8; font-weight: bold; }
    </style>

    <div class="bottom-nav">
        <a href="index.html" class="nav-item ${currentPage === 'index.html' ? 'active' : ''}"><i>🏠</i><span>Home</span></a>
        <a href="wallet.html" class="nav-item ${currentPage === 'wallet.html' ? 'active' : ''}"><i>🪙</i><span>Wallet</span></a>
        <a href="camera.html" class="nav-item"><div class="upload-plus-btn">+</div></a>
        <a href="live.html" class="nav-item live-btn-nav ${currentPage === 'live.html' ? 'active' : ''}"><i>🔴</i><span>LIVE</span></a>
        <a href="gaming.html" class="nav-item game-btn-nav ${currentPage === 'gaming.html' ? 'active' : ''}"><i>🎮</i><span>Gaming</span></a>
        <a href="inbox.html" class="nav-item ${currentPage === 'inbox.html' ? 'active' : ''}"><i>💬</i><span>Inbox</span></a>
        <a href="profile.html" class="nav-item ${currentPage === 'profile.html' ? 'active' : ''}"><i>⭐</i><span>Profile</span></a>
    </div>`;

    // Automatically inject footer into body
    let footerContainer = document.createElement('div');
    footerContainer.innerHTML = navHTML;
    document.body.appendChild(footerContainer);
});
