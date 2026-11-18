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
    const titleInput = document.getElementById('videoCaptionInput');
    const descInput = document.getElementById('videoDescriptionInput');
    const visibilityInput = document.getElementById('videoVisibilitySelect');
    const titleText = titleInput ? titleInput.value.trim().slice(0, 180) : 'Video';
    const descriptionText = descInput ? descInput.value.trim().slice(0, 2000) : '';

    if (!finalMergedVideoBlob || !finalMergedVideoBlob.size) {
        showToast('⚠️ वीडियो नहीं मिला!');
        return;
    }
    if (!navigator.onLine) {
        showToast('इंटरनेट कनेक्शन उपलब्ध नहीं है।');
        return;
    }

    let uploadedPath = null;
    showToast('🔐 सुरक्षित रूप से अपलोड हो रहा है...');
    try {
        const { data: authData, error: authError } = await cloudClient.auth.getUser();
        if (authError) throw authError;
        const user = authData && authData.user;
        if (!user || !user.id) throw new Error('वीडियो अपलोड करने के लिए पहले Login करें।');

        const visibilityValue = visibilityInput ? visibilityInput.value : 'private';
        const visibility = ['public', 'unlisted', 'private'].includes(visibilityValue)
            ? visibilityValue : 'private';

        const profileResult = await cloudClient.from('profiles')
            .select('username').eq('user_id', String(user.id)).maybeSingle();
        if (profileResult.error) throw profileResult.error;

        const extension = finalMergedVideoBlob.type.includes('webm') ? 'webm'
            : finalMergedVideoBlob.type.includes('quicktime') ? 'mov'
            : finalMergedVideoBlob.type.includes('mp4') ? 'mp4' : 'webm';
        uploadedPath = String(user.id) + '/merged_' + crypto.randomUUID() + '.' + extension;

        const { error: uploadError } = await cloudClient.storage.from('videos').upload(
            uploadedPath,
            finalMergedVideoBlob,
            { cacheControl: '3600', upsert: false, contentType: finalMergedVideoBlob.type || 'video/webm' }
        );
        if (uploadError) throw uploadError;

        const videoUrl = 'storage://videos/' + uploadedPath;
        const videoElement = document.getElementById('selectedVideoPreview');
        const duration = videoElement && Number.isFinite(videoElement.duration)
            ? Math.max(1, Math.ceil(videoElement.duration)) : null;
        const width = videoElement && videoElement.videoWidth ? videoElement.videoWidth : null;
        const height = videoElement && videoElement.videoHeight ? videoElement.videoHeight : null;
        const videoType = duration && duration <= 60 ? 'short'
            : width && height && width > height ? 'long' : 'short';

        const { data: inserted, error: insertError } = await cloudClient.from('videos')
            .insert({
                user_id: String(user.id),
                video_url: videoUrl,
                caption: titleText,
                category: 'Other',
                username: profileResult.data?.username || user.user_metadata?.username || 'Creator',
                likes_count: 0,
                views_count: 0,
                audience_type: 'all',
                duration_seconds: duration,
                width,
                height,
                video_type: videoType,
                processing_status: 'published',
                visibility
            })
            .select('id').single();

        if (insertError) throw insertError;
        if (!inserted?.id) throw new Error('Database ने Video ID नहीं लौटाई।');

        showToast('✨ वीडियो सुरक्षित रूप से सेव हो गया!');
        window.location.href = 'profile.html';
    } catch (err) {
        if (uploadedPath) {
            try { await cloudClient.storage.from('videos').remove([uploadedPath]); }
            catch (cleanupError) { console.warn('Failed upload cleanup:', cleanupError); }
        }
        showToast('❌ Upload Error: ' + (err?.message || 'कृपया दोबारा कोशिश करें।'));
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
