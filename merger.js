// Reuse the one shared authenticated Supabase client configured by supabase-config.js.
const cloudClient = window.supabaseClient || window.cloudClient || null;

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
    const db = window.supabaseClient || window.cloudClient || cloudClient;
    const titleInput = document.getElementById('videoCaptionInput');
    const descInput = document.getElementById('videoDescriptionInput');
    const titleText = titleInput ? titleInput.value.trim() : '';
    const descriptionText = descInput ? descInput.value.trim() : '';
    const source = finalMergedVideoBlob || selectedVideoFile;

    if (!db?.auth || !db?.storage) {
        showToast('Supabase connection उपलब्ध नहीं है।');
        return;
    }
    if (!source || !source.size) {
        showToast('वीडियो फ़ाइल नहीं मिली।');
        return;
    }
    const mimeType = String(source.type || '').toLowerCase().split(';')[0].trim();
    if (!mimeType.startsWith('video/')) {
        showToast('वीडियो का सही MIME type नहीं मिला। दोबारा वीडियो चुनें।');
        return;
    }

    let uploadedPath = null;
    let databaseSaved = false;
    try {
        const { data: authData, error: authError } = await db.auth.getUser();
        if (authError) throw authError;
        const user = authData?.user;
        if (!user) {
            location.href = 'auth.html?next=camera.html';
            return;
        }

        const extensionByMime = {
            'video/mp4': 'mp4',
            'video/webm': 'webm',
            'video/quicktime': 'mov',
            'video/3gpp': '3gp',
            'video/x-m4v': 'm4v'
        };
        const extension = extensionByMime[mimeType] ||
            ((source.name || '').split('.').pop() || '').toLowerCase().replace(/[^a-z0-9]/g, '');
        if (!extension) throw new Error('इस वीडियो फ़ॉर्मेट का extension पहचाना नहीं गया।');

        const objectUrl = URL.createObjectURL(source);
        let mediaInfo;
        try {
            mediaInfo = await new Promise((resolve, reject) => {
                const probe = document.createElement('video');
                probe.preload = 'metadata';
                probe.muted = true;
                const timeout = setTimeout(() => reject(new Error('वीडियो की जानकारी पढ़ने में समय समाप्त हुआ।')), 15000);
                const cleanup = () => {
                    clearTimeout(timeout);
                    probe.onloadedmetadata = null;
                    probe.onerror = null;
                    probe.removeAttribute('src');
                    probe.load();
                };
                probe.onloadedmetadata = () => {
                    const info = {
                        duration: Number(probe.duration) || 0,
                        width: Number(probe.videoWidth) || 0,
                        height: Number(probe.videoHeight) || 0
                    };
                    cleanup();
                    if (!info.duration || !info.width || !info.height) {
                        reject(new Error('वीडियो metadata अधूरा है।'));
                    } else resolve(info);
                };
                probe.onerror = () => {
                    cleanup();
                    reject(new Error('यह वीडियो इस ब्राउज़र में पढ़ा नहीं जा सका।'));
                };
                probe.src = objectUrl;
                probe.load();
            });
        } finally {
            URL.revokeObjectURL(objectUrl);
        }

        if (mediaInfo.duration > 10800) throw new Error('3 घंटे से लंबा वीडियो अभी समर्थित नहीं है।');
        const fileId = typeof crypto.randomUUID === 'function'
            ? crypto.randomUUID()
            : Date.now() + '-' + Math.random().toString(36).slice(2);
        const filePath = user.id + '/' + fileId + '.' + extension;
        const { error: uploadError } = await db.storage.from('videos').upload(
            filePath,
            source,
            { cacheControl: '3600', upsert: false, contentType: mimeType }
        );
        if (uploadError) throw uploadError;
        uploadedPath = filePath;

        const { data: urlData } = db.storage.from('videos').getPublicUrl(filePath);
        const publicVideoUrl = urlData?.publicUrl;
        if (!publicVideoUrl) throw new Error('वीडियो का public URL नहीं मिला।');

        let username = user.user_metadata?.username ||
            user.user_metadata?.full_name || user.email?.split('@')[0] || 'New Star Creator';
        try {
            const { data: profile } = await db.from('profiles')
                .select('username,full_name').eq('user_id', user.id).maybeSingle();
            if (profile) username = profile.username || profile.full_name || username;
        } catch (profileError) {
            console.warn('Profile name lookup skipped:', profileError);
        }

        const caption = [titleText, descriptionText].filter(Boolean).join('\\n\\n') || 'New Star Video';
        const categoryInput = document.getElementById('videoCategory');
        const audienceInput = document.getElementById('audienceType');
        const payload = {
            user_id: user.id,
            video_url: publicVideoUrl,
            caption,
            category: categoryInput?.value || 'General',
            username: String(username),
            likes_count: 0,
            views_count: 0,
            audience_type: audienceInput?.value || 'all',
            duration_seconds: Math.ceil(mediaInfo.duration),
            width: mediaInfo.width,
            height: mediaInfo.height,
            video_type: mediaInfo.duration <= 60 ? 'short' : 'long',
            processing_status: 'published',
            visibility: 'public'
        };
        const { data: inserted, error: insertError } = await db.from('videos')
            .insert(payload).select('id').single();
        if (insertError) throw new Error('Database Insert: ' + insertError.message);
        if (!inserted?.id) throw new Error('Database से Video ID नहीं मिली।');

        databaseSaved = true;
        showToast('वीडियो सफलतापूर्वक Publish हो गया!');
        setTimeout(() => { window.location.href = 'index.html?video=' + encodeURIComponent(inserted.id); }, 800);
    } catch (err) {
        console.error('Publish Error:', err);
        if (uploadedPath && !databaseSaved) {
            try {
                const { error: cleanupError } = await db.storage.from('videos').remove([uploadedPath]);
                if (cleanupError) console.warn('Storage cleanup:', cleanupError);
            } catch (cleanupError) {
                console.warn('Storage cleanup failed:', cleanupError);
            }
        }
        showToast('Publish नहीं हुआ: ' + (err?.message || 'कृपया दोबारा कोशिश करें।'));
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
