const STORAGE_API_URL = "https://uzsusgoetrkujmofxcmb.supabase.co";
const STORAGE_API_KEY = "sb_publishable_Zz2hljevMsXBcK8c33UvFw_wuc2itVr";
const { createClient } = window.supabase;
const cloudClient = createClient(STORAGE_API_URL, STORAGE_API_KEY);

let selectedVideoFile = null;
let finalMergedVideoBlob = null;
let mediaRecorderDirect = null;
let directRecordedChunks = [];
let directCameraStreamObj = null;
let cameraTimerInterval = null;
let cameraSecondsElapsed = 0;

function switchStudioMode(mode) {
    if(mode === 'video') {
        let sec = document.getElementById('sectionVideo');
        if(sec) sec.classList.add('active');
        if (!selectedVideoFile) {
            let mediaActions = document.getElementById('mediaActionsRow');
            if(mediaActions) mediaActions.style.display = 'flex';
        }
    } else {
        let mediaActions = document.getElementById('mediaActionsRow');
        if(mediaActions) mediaActions.style.display = 'none';
    }
}

function onVideoFileChosen(event) {
    let file = event.target.files[0];
    if(file) {
        selectedVideoFile = file;
        finalMergedVideoBlob = file;
        
        let mediaActions = document.getElementById('mediaActionsRow');
        if(mediaActions) mediaActions.style.display = 'none';
        
        let videoElement = document.getElementById('selectedVideoPreview');
        if(videoElement) videoElement.src = URL.createObjectURL(file);
        
        let previewBox = document.getElementById('previewBox');
        if(previewBox) previewBox.style.display = 'block';
        
        let formContainer = document.getElementById('videoDetailsFormContainer');
        if(formContainer) formContainer.style.display = 'block';
        
        showToast('📁 वीडियो लोड हो गया है, अब एडिट और पोस्ट करें!');
    }
}

async function openDirectCameraStudio() {
    let studioBox = document.getElementById('directCameraStudio');
    if(studioBox) studioBox.style.display = 'flex';
    
    let topTabs = document.getElementById('topModeTabsRow');
    if(topTabs) topTabs.style.display = 'none';
    
    let mediaActions = document.getElementById('mediaActionsRow');
    if(mediaActions) mediaActions.style.display = 'none';

    try {
        directCameraStreamObj = await navigator.mediaDevices.getUserMedia({ 
            video: { facingMode: 'user', width: { ideal: 1280 }, height: { ideal: 720 } }, 
            audio: true 
        });
        let camVideoEl = document.getElementById('directCameraFeed');
        if(camVideoEl) {
            camVideoEl.srcObject = directCameraStreamObj;
            camVideoEl.play();
        }
        showToast('🎥 कैमरा चालू है!');
    } catch(err) {
        showToast('❌ Camera Error: ' + err.message);
    }
}

function startDirectCameraRecording() {
    if(!directCameraStreamObj) return;
    directRecordedChunks = [];
    
    let options = { mimeType: 'video/webm;codecs=vp9,opus' };
    if (!MediaRecorder.isTypeSupported(options.mimeType)) {
        options = { mimeType: 'video/webm' };
    }

    try {
        mediaRecorderDirect = new MediaRecorder(directCameraStreamObj, options);
    } catch (e) {
        mediaRecorderDirect = new MediaRecorder(directCameraStreamObj);
    }

    mediaRecorderDirect.ondataavailable = (e) => {
        if(e.data.size > 0) directRecordedChunks.push(e.data);
    };

    mediaRecorderDirect.onstop = () => {
        let blob = new Blob(directRecordedChunks, { type: 'video/webm' });
        selectedVideoFile = blob;
        finalMergedVideoBlob = blob;

        // 1. कैमरा स्टूडियो बॉक्स को पूरी तरह छिपा दो
        let studioBox = document.getElementById('directCameraStudio');
        if(studioBox) studioBox.style.display = 'none';

        // 2. वीडियो प्रीव्यू बॉक्स को चालू करो और रिकॉर्ड हुआ वीडियो सेट करो
        let videoElement = document.getElementById('selectedVideoPreview');
        if(videoElement) videoElement.src = URL.createObjectURL(blob);

        let previewBox = document.getElementById('previewBox');
        if(previewBox) previewBox.style.display = 'block';

        // 3. टाइटल, डिस्क्रिप्शन और एडिटर वाले फॉर्म को साफ़-साफ़ दिखाओ
        let formContainer = document.getElementById('videoDetailsFormContainer');
        if(formContainer) {
            formContainer.style.display = 'block';
            formContainer.scrollIntoView({ behavior: 'smooth' });
        }
        
        showToast('✅ वीडियो सेव होकर एडिटर और पब्लिशिंग सेक्शन में आ गया है!');
    };

    mediaRecorderDirect.start(1000);
    cameraSecondsElapsed = 0;
    let badge = document.getElementById('cameraTimerBadge');
    if(badge) badge.style.display = 'block';
    
    cameraTimerInterval = setInterval(() => {
        cameraSecondsElapsed++;
        let mins = Math.floor(cameraSecondsElapsed / 60);
        let secs = cameraSecondsElapsed % 60;
        if(badge) {
            badge.innerText = `⏱️ ${mins < 10 ? '0' + mins : mins}:${secs < 10 ? '0' + secs : secs}`;
        }
    }, 1000);

    let recBtn = document.getElementById('camRecordBtn');
    let stopBtn = document.getElementById('camStopBtn');
    if(recBtn) recBtn.style.display = 'none';
    if(stopBtn) stopBtn.style.display = 'block';
}

function stopDirectCameraRecording() {
    if(mediaRecorderDirect && mediaRecorderDirect.state === 'recording') {
        mediaRecorderDirect.stop();
        clearInterval(cameraTimerInterval);
        let badge = document.getElementById('cameraTimerBadge');
        if(badge) badge.style.display = 'none';
        
        if(directCameraStreamObj) {
            directCameraStreamObj.getTracks().forEach(track => track.stop());
        }
    }
}

async function publishVideoToGlobalCloudStorage() {
    let titleInput = document.getElementById('videoCaptionInput');
    let descInput = document.getElementById('videoDescriptionInput');
    
    let titleText = titleInput ? titleInput.value.trim() : 'Viral Video';
    let descriptionText = descInput ? descInput.value.trim() : '';

    if(!finalMergedVideoBlob) {
        showToast('⚠️ वीडियो नहीं मिला!');
        return;
    }

    showToast('🚀 क्लाउड पर अपलोड हो रहा है...');

    try {
        let fileName = 'global_merged_' + Date.now() + '.mp4';
        let { error: uploadError } = await cloudClient.storage
            .from('videos')
            .upload(fileName, finalMergedVideoBlob, { cacheControl: '3600', upsert: true });

        if (uploadError) throw new Error(uploadError.message);

        let { data: urlData } = cloudClient.storage.from('videos').getPublicUrl(fileName);
        let publicVideoUrl = urlData ? urlData.publicUrl : '';

        await cloudClient.from('videos').insert([
            { video_url: publicVideoUrl, caption: titleText, description: descriptionText, likes_count: 15 }
        ]);

        showToast('✨ सफलतापूर्वक पब्लिश हो गया!');
        setTimeout(() => { window.location.href = 'profile.html'; }, 1000);
    } catch(err) {
        showToast('❌ Error: ' + err.message);
    }
}

function toggleMasterThreeDotMenu() {
    let menu = document.getElementById('masterThreeDotMenu');
    if(!menu) return;
    menu.style.display = (menu.style.display === 'block') ? 'none' : 'block';
}

function openThreeDotSub(subName) {
    document.querySelectorAll('.three-dot-sub-section').forEach(el => el.classList.remove('active'));
    let target = document.getElementById('sub_' + subName);
    if(target) target.classList.add('active');
}

function showToast(msg) {
    let toast = document.getElementById('floatingToastMessage');
    if(!toast) return;
    toast.innerText = msg;
    toast.classList.add('show');
    setTimeout(() => { toast.classList.remove('show'); }, 3000);
}
