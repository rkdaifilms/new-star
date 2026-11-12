let currentTargetSetting = 'main';
let mainScale = 1, mainX = 0, mainY = 0;
let duetScale = 1, duetX = 0, duetY = 0;
let isDragging = false, startX = 0, startY = 0;

function showToast(message) {
    let toast = document.getElementById('floatingToastMessage');
    toast.innerText = message;
    toast.classList.add('show');
    setTimeout(() => { toast.classList.remove('show'); }, 2500);
}

function selectVideoTarget(targetType, event) {
    if(event) event.stopPropagation();
    currentTargetSetting = targetType;
    showToast(targetType === 'duet' ? '👥 छोटा ड्युएट वीडियो सेलेक्ट है' : '⚙️ बड़ा मेन वीडियो सेलेक्ट है');
}

function scaleTargetVideo(factor) {
    let targetEl = currentTargetSetting === 'main' ? document.getElementById('selectedVideoPreview') : document.getElementById('overlayTrackContainer');
    if (currentTargetSetting === 'main') {
        mainScale = Math.max(0.5, Math.min(3.5, mainScale * factor));
        if(targetEl) targetEl.style.transform = `translate(${mainX}px, ${mainY}px) scale(${mainScale})`;
        showToast(`🔍 ज़ूम: ${Math.round(mainScale * 100)}%`);
    }
}

// ✍️ Text Overlay
function addTextOverlayToVideo() {
    let textInput = document.getElementById('videoCustomTextInput');
    if(!textInput || !textInput.value.trim()) {
        showToast('⚠️ कृपया पहले टेक्स्ट लिखें!');
        return;
    }
    let textValue = textInput.value.trim();
    let previewBox = document.getElementById('previewBox');
    if(!previewBox) return;

    let existingTextDiv = document.getElementById('liveVideoTextOverlay');
    if(existingTextDiv) existingTextDiv.remove();

    let textDiv = document.createElement('div');
    textDiv.id = 'liveVideoTextOverlay';
    textDiv.innerText = textValue;
    textDiv.style.position = 'absolute';
    textDiv.style.top = '50%';
    textDiv.style.left = '50%';
    textDiv.style.transform = 'translate(-50%, -50%)';
    textDiv.style.color = '#fff';
    textDiv.style.background = 'rgba(0, 0, 0, 0.6)';
    textDiv.style.padding = '8px 16px';
    textDiv.style.borderRadius = '8px';
    textDiv.style.fontSize = '18px';
    textDiv.style.fontWeight = 'bold';
    textDiv.style.zIndex = '30';
    textDiv.style.cursor = 'grab';
    textDiv.style.border = '1px solid #25f4ee';

    previewBox.appendChild(textDiv);
    showToast('✍️ वीडियो पर टेक्स्ट जोड़ दिया गया है!');
    textInput.value = '';
}

// 🔊 Audio Booster (200%-300%)
function boostVideoAudio(boostMultiplier) {
    let targetEl = document.getElementById('selectedVideoPreview');
    if(!targetEl) return;
    try {
        let audioCtx = new (window.AudioContext || window.webkitAudioContext)();
        let source = audioCtx.createMediaElementSource(targetEl);
        let gainNode = audioCtx.createGain();
        gainNode.gain.value = boostMultiplier;
        source.connect(gainNode);
        gainNode.connect(audioCtx.destination);
        showToast(`🔊 आवाज़ को ${boostMultiplier * 100}% तक तेज़/बूस्ट कर दिया गया है!`);
    } catch(err) {
        targetEl.volume = 1.0;
        showToast('🔊 वॉल्यूम फुल कर दिया गया है!');
    }
}

// 🎵 Background Music & SFX
function loadBackgroundMusic(event) {
    let file = event.target.files[0];
    if(file) {
        let musicUrl = URL.createObjectURL(file);
        let existingAudio = document.getElementById('backgroundAudioElement');
        if(existingAudio) existingAudio.remove();

        let audioEl = document.createElement('audio');
        audioEl.id = 'backgroundAudioElement';
        audioEl.src = musicUrl;
        audioEl.loop = true;
        audioEl.volume = 0.5;
        document.body.appendChild(audioEl);
        audioEl.play();
        showToast('🎵 बैकग्राउंड म्यूजिक / साउंड इफ़ेक्ट जुड़ गया है!');
    }
}

// 🎙️ Overvoice Recording
let mediaRecorderVoice = null;
let voiceAudioChunks = [];
function startVoiceoverRecording() {
    navigator.mediaDevices.getUserMedia({ audio: true }).then(stream => {
        mediaRecorderVoice = new MediaRecorder(stream);
        voiceAudioChunks = [];
        mediaRecorderVoice.ondataavailable = e => voiceAudioChunks.push(e.data);
        mediaRecorderVoice.onstop = () => {
            let audioBlob = new Blob(voiceAudioChunks, { type: 'audio/webm' });
            let audioUrl = URL.createObjectURL(audioBlob);
            let voiceEl = document.createElement('audio');
            voiceEl.id = 'userVoiceoverElement';
            voiceEl.src = audioUrl;
            voiceEl.controls = true;
            document.body.appendChild(voiceEl);
            voiceEl.play();
            showToast('🎙️ ओवरवॉइस रिकॉर्ड होकर जुड़ गई है!');
        };
        mediaRecorderVoice.start();
        showToast('🔴 ओवरवॉइस रिकॉर्डिंग चालू है...');
    }).catch(err => {
        showToast('❌ Mic Error: ' + err.message);
    });
}
function stopVoiceoverRecording() {
    if(mediaRecorderVoice && mediaRecorderVoice.state === 'recording') {
        mediaRecorderVoice.stop();
        showToast('⏹️ ओवरवॉइस रिकॉर्डिंग रोक दी गई है।');
    }
}

// ⚡ Video Speed Controller Slider
function changeVideoSpeed(speedValue) {
    let targetEl = document.getElementById('selectedVideoPreview');
    let speedTextEl = document.getElementById('videoSpeedLabel');
    if(targetEl) targetEl.playbackRate = parseFloat(speedValue);
    if(speedTextEl) speedTextEl.innerText = speedValue + 'x';
    showToast(`⚡ वीडियो स्पीड: ${speedValue}x`);
}

// Drag & Touch support
const previewBoxEl = document.getElementById('previewBox');
if(previewBoxEl) {
    previewBoxEl.addEventListener('pointerdown', (e) => {
        isDragging = true;
        startX = e.clientX - mainX;
        startY = e.clientY - mainY;
        previewBoxEl.setPointerCapture(e.pointerId);
    });
    previewBoxEl.addEventListener('pointermove', (e) => {
        if (!isDragging) return;
        mainX = e.clientX - startX;
        mainY = e.clientY - startY;
        let v = document.getElementById('selectedVideoPreview');
        if(v) v.style.transform = `translate(${mainX}px, ${mainY}px) scale(${mainScale})`;
    });
    previewBoxEl.addEventListener('pointerup', (e) => {
        isDragging = false;
        try { previewBoxEl.releasePointerCapture(e.pointerId); } catch(err) {}
    });
}

function toggleMasterThreeDotMenu() {
    let menu = document.getElementById('masterThreeDotMenu');
    if(menu) menu.style.display = menu.style.display === 'block' ? 'none' : 'block';
}
