const read = (path: string) => Deno.readTextFile(new URL("../" + path, import.meta.url));
function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}
function blockFor(source: string, marker: string, nextMarker: string) {
  const start = source.indexOf(marker);
  assert(start >= 0, "Missing route marker: " + marker);
  const end = source.indexOf(nextMarker, start + marker.length);
  return source.slice(start, end < 0 ? source.length : end);
}

Deno.test("Home AI chat is private New Star only; Help and Support is Gemini only", async () => {
  const source = await read("supabase/functions/ai-gateway/index.ts");
  const chat = blockFor(source, "const providers = body.mode === \"help\"", "if (providers.length === 0)");
  assert(chat.includes("body.mode === \"help\""), "Help routing selector missing");
  assert(chat.includes("run: callGemini"), "Help route must use Gemini");
  assert(chat.includes("selfHostedOnly: true"), "Home route must enforce private-only routing");
  assert(chat.includes("geminiKeys: []") && chat.includes("openaiKeys: []") && chat.includes("openrouterKeys: []"),
    "Home route must not pass external provider keys");
});

Deno.test("Home status hides external providers while Help status checks Gemini", async () => {
  const source = await read("supabase/functions/ai-gateway/index.ts");
  const status = blockFor(source, 'if (body?.action === "status")', 'if (!Array.isArray(body?.messages)');
  assert(status.includes("const helpMode = body.mode === \"help\""), "Status route must distinguish Help mode");
  assert(status.includes("gemini: geminiConfigured"), "Status must report Gemini only for Help mode");
  assert(status.includes("openai: false") && status.includes("openrouter: false"), "External providers must be off");
  const help = await read("ai-chat.js");
  assert(help.includes('body:{action:"status",mode:"help"}'), "Help UI must request Help-specific Gemini status");
  assert(help.includes('body:{messages:requestMessages,mode:"help"}'), "Help chat must use the support system prompt");
});

Deno.test("Home media and voice routes fail closed before external provider access", async () => {
  const source = await read("supabase/functions/ai-gateway/index.ts");
  const routes = [
    ["vision", "NEW_STAR_VISION_MODEL_NOT_DEPLOYED"],
    ["speak", "NEW_STAR_SPEECH_MODEL_NOT_DEPLOYED"],
    ["transcribe", "NEW_STAR_STT_MODEL_NOT_DEPLOYED"],
    ["video", "NEW_STAR_VIDEO_MODEL_NOT_DEPLOYED"],
    ["video-status", "NEW_STAR_VIDEO_WORKER_NOT_DEPLOYED"],
    ["image", "NEW_STAR_IMAGE_MODEL_NOT_DEPLOYED"]
  ];
  for (const [action, code] of routes) {
    const block = blockFor(source, 'if (body?.action === "' + action + '")', 'if (body?.action === "');
    assert(block.includes(code), action + " route missing private worker guard");
    const firstProvider = [
      block.indexOf("secretCandidates("), block.indexOf("fetchWithTimeout("),
      block.indexOf("generateGemini"), block.indexOf("generateOpenAI"),
      block.indexOf("startGeminiVideo("), block.indexOf("getGeminiVideoInteraction(")
    ].filter((index) => index >= 0).sort((a, b) => a - b)[0];
    assert(firstProvider === undefined || block.indexOf(code) < firstProvider,
      action + " route reaches an external provider before failing closed");
  }
  const search = blockFor(source, 'if (body?.action === "search")', 'if (body?.action === "vision")');
  assert(search.includes("searchNewStarIndex"), "Search must use New Star's private index");
  assert(search.includes("NEW_STAR_SEARCH_WORKER_NOT_DEPLOYED"), "Search must report missing private search worker");
  assert(!search.includes("searchWithGeminiGrounding"), "Home search must not fall back to Gemini");
});

Deno.test("Voice Send stops, transcribes, and automatically submits the recording", async () => {
  const source = await read("new-star-ai.js");
  assert(source.includes("voiceSendPending=true"), "Send while recording must set auto-send state");
  assert(source.includes("await transcribeVoice(blob)"), "Recording must be transcribed before sending");
  assert(source.includes("form.requestSubmit()"), "Transcribed voice must automatically submit");
  assert(source.includes("voiceSendPending=false;submit.disabled=false;mic.disabled=false;resetMicButton()"),
    "Empty recordings must restore the Send and microphone controls");
  assert(source.includes("voiceSendPending=false;submit.disabled=false;"), "Recorder errors must restore controls");
});

Deno.test("Image and video prompts in chat select their matching generation mode", async () => {
  const source = await read("new-star-ai.js");
  assert(source.includes("function detectMediaMode(text)"), "Media intent detection missing");
  assert(source.includes('if(videoIntent)return "video"'), "Video prompt detection missing");
  assert(source.includes('if(imageIntent)return "image"'), "Image prompt detection missing");
  assert(source.includes("const detectedMode=detectMediaMode(sendText)"), "Chat submit must detect image/video intent");
});

Deno.test("Duplicate Creator Studio AI entry and its settings are removed", async () => {
  const settings = await read("settings.html");
  const navbar = await read("navbar.js");
  const page = await read("creator-ai.html");
  const app = await read("new-star-ai.html");
  const js = await read("new-star-ai.js");
  assert(!settings.includes("New Star Creator Studio AI"), "Duplicate AI settings row remains");
  assert(!navbar.includes('href="creator-ai.html"'), "Duplicate AI navigation link remains");
  assert(page.includes('location.replace("new-star-ai.html")'), "Old Creator Studio URL must redirect to the single AI engine");
  assert(!app.includes("Developer API keys"), "Home AI API-key settings UI remains");
  assert(!js.includes("loadApiKeys") && !js.includes("apiKeyRequest") && !js.includes("ns-api-form"),
    "Home AI still contains old API-key settings handlers");
});

Deno.test("Unavailable private AI capabilities are disabled in the home UI", async () => {
  const source = await read("new-star-ai.js");
  assert(source.includes("function applyCapabilityAvailability(capabilities)"), "Capability availability gate missing");
  assert(source.includes("research:Boolean(capabilities.webSearch?.configured)"), "Research must require a deployed private search worker");
  assert(source.includes("image:Boolean(capabilities.imageGeneration?.configured)"), "Image mode must require a deployed private image worker");
  assert(source.includes("video:Boolean(capabilities.videoGeneration?.configured)"), "Video mode must require a deployed private video worker");
  assert(source.includes("const ready=Boolean(capabilities.speechTranscription?.configured)"), "Voice input must require a deployed private transcription worker");
  assert(source.includes("applyCapabilityAvailability(capabilities);"), "Status response must apply capability gates");
  assert(source.includes("if(next!==\"chat\"&&!capabilityAvailability[next])"), "Unavailable modes must not be selectable");
  assert(source.includes("!capabilityAvailability[detectedMode]"), "Auto-detected unavailable media requests must not be submitted as chat");
  const html = await read("new-star-ai.html");
  assert(html.includes('data-mode="image" disabled aria-disabled="true"'), "Image mode must start disabled before status loads");
  assert(html.includes('id="ns-mic" type="button" disabled aria-disabled="true"'), "Voice must start disabled before status loads");
});

Deno.test("Help AI launcher is not injected into the home feed", async () => {
  const source = await read("ai-chat.js");
  assert(source.includes('if(!onHomeFeed){document.body.append(launch,panel);}'),
    "Help AI must only be mounted in the Help and Support experience");
});
