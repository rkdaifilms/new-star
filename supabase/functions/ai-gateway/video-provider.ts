export function buildGeminiVideoRequest(
  model: string,
  prompt: string,
  aspectRatio: string,
  resolution: string,
  duration: string
) {
  return {
    model,
    input: [{ type: "text", text: prompt }],
    generation_config: { video_config: { task: "text_to_video" } },
    response_format: [{
      type: "video",
      delivery: "inline",
      aspect_ratio: aspectRatio,
      resolution,
      duration
    }],
    background: true,
    store: true
  };
}
