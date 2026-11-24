import { buildGeminiVideoRequest } from "./video-provider.ts";

Deno.test("Gemini video request uses typed text input and background interaction", () => {
  const request = buildGeminiVideoRequest("gemini-omni-1.2-flash", "A calm mountain sunrise", "16:9", "720p", "15s");
  if (request.model !== "gemini-omni-1.2-flash") throw new Error("Model was not preserved.");
  if (!Array.isArray(request.input) || request.input.length !== 1) throw new Error("Expected one typed input item.");
  if (request.input[0].type !== "text" || request.input[0].text !== "A calm mountain sunrise") throw new Error("Text input shape is invalid.");
  if (request.response_format.length !== 1 || request.response_format[0].type !== "video") throw new Error("Video response format is invalid.");
  if (request.response_format[0].duration !== "15s" || request.response_format[0].delivery !== "inline") throw new Error("Video output settings are invalid.");
  if (request.generation_config.video_config.task !== "text_to_video" || request.background !== true || request.store !== true) throw new Error("Background video interaction settings are invalid.");
});
