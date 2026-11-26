export function buildGeminiVideoRequest(
  model: string,
  prompt: string,
  aspectRatio: string,
  resolution: string,
  duration: string
) {
  const allowedDurations = model === "gemini-omni-1.2-flash"
    ? ["3s", "4s", "5s", "6s", "7s", "8s", "9s", "10s", "11s", "12s", "13s", "14s", "15s"]
    : ["3s", "4s", "5s", "6s", "7s", "8s", "9s", "10s"];
  const safeDuration = allowedDurations.includes(duration) ? duration : "6s";
  const safeAspectRatio = aspectRatio === "9:16" ? "9:16" : "16:9";
  const safeResolution = ["360p", "720p", "1080p"].includes(resolution) ? resolution : "720p";

  return {
    model,
    background: true,
    store: true,
    input: [{ type: "text", text: prompt }],
    response_format: [{
      type: "video",
      delivery: "inline",
      aspect_ratio: safeAspectRatio,
      resolution: safeResolution,
      duration: safeDuration
    }],
    generation_config: { video_config: { task: "text_to_video" } }
  };
}
