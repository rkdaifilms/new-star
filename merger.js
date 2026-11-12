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
const MAX_RECORDING_SECONDS = 600; // 10 Minutes max

function switchStudioMode(mode) {
    if(mode === 'video') {
        document.getElementById('sectionVideo').classList.add('active');
        if (!selectedVideoFile) document.getElementById('mediaActionsRow').style.display = 'flex';
    } else {
        document.getElementById('mediaActionsRow').style.display = 'none';
    }
}

function onVideoFileChosen(event) {
    let file = event.target.files[0];
    if(file) {
        selectedVideoFile = file;
        finalMergedVideoBlob = file;
        document.getElementById('mediaActionsRow').style.display = 'none';
        let videoElement = document.getElementById('selectedVideoPreview');
        videoElement.src = URL.createObjectURL(file);
        document.getElementById('previewBox').style.display = 'block';
        document.getElementById('videoDetailsFormContainer').style.display = 'block';
        showToast('📁 वीडियो लोड हो गया है, अब पोस्ट करें!');
    }
}

async function openDirectCameraStudio() {
    let studioBox = document.getElementById('directCameraStudio');
    studioBox.style.display = 'block';
    document.getElementById('mediaActionsRow').style.display = 'none';

    try {
        directCameraStreamObj = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user' }, audio: true });
        let camVideoEl = document.getElementById('directCameraFeed');
        camVideoEl.srcObject = directCameraStreamObj;
        camVideoEl.play();
        showToast('🎥 कैमरा चालू है!');
    } catch(err) {
        showToast('❌ Camera Error: ' + err.message);
    }
}

function startDirectCameraRecording() {
    if(!directCameraStreamObj) return;
    directRecordedChunks = [];
    mediaRecorderDirect = new MediaRecorder(directCameraStreamObj, { mimeType: 'video/webm' });

    mediaRecorderDirect.ondataavailable = (e) => {
        if(e.data.size > 0) directRecordedChunks.push(e.data);
    };

    mediaRecorderDirect.onstop = () => {
        let blob = new Blob(directRecordedChunks, { type: 'video/webm' });
        selectedVideoFile = blob;
        finalMergedVideoBlob = blob;

        document.getElementById('directCameraStudio').style.display = 'none';
        let videoElement = document.getElementById('selectedVideoPreview');
        videoElement.src = URL.createObjectURL(blob);
        document.getElementById('previewBox').style.display = 'block';
        document.getElementById('videoDetailsFormContainer').style.display = 'block';
        showToast('✅ वीडियो रिकॉर्ड होकर पब्लिशिंग सेक्शन में आ गया है!');
    };

    mediaRecorderDirect.start();
    cameraSecondsElapsed = 0;
    let badge = document.getElementById('cameraTimerBadge');
    badge.style.display = 'block';
    
    cameraTimerInterval = setInterval(() => {
        cameraSecondsElapsed++;
        let mins = Math.floor(cameraSecondsElapsed / 60);
        let secs = cameraSecondsElapsed % 60;
        badge.innerText = `⏱️ ${mins < 10 ? '0' + mins : mins}:${secs < 10 ? '0' + secs : secs} / 10:00`;
        
        if (cameraSecondsElapsed >= MAX_RECORDING_SECONDS) {
            stopDirectCameraRecording();
        }
    }, 1000);

    document.getElementById('camRecordBtn').style.display = 'none';
    document.getElementById('camStopBtn').style.display = 'block';
}

function stopDirectCameraRecording() {
    if(mediaRecorderDirect && mediaRecorderDirect.state === 'recording') {
        mediaRecorderDirect.stop();
        clearInterval(cameraTimerInterval);
        document.getElementById('cameraTimerBadge').style.display = 'none';
        if(directCameraStreamObj) {
            directCameraStreamObj.getTracks().forEach(track => track.stop());
        }
    }
}

async function publishVideoToGlobalCloudStorage() {
    let titleText = document.getElementById('videoCaptionInput').value.trim() || 'Viral Video';
    let descriptionText = document.getElementById('videoDescriptionInput').value.trim() || '';

    if(!finalMergedVideoBlob) {
        showToast('⚠️ वीडियो नहीं मिला!');
        return;
    }

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
