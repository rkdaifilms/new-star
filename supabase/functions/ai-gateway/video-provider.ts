export function buildGeminiVideoRequest(
  model: string,
  prompt: string,
  aspectRatio: string,
  resolution: string,
  duration: string
) {
  return {
    model,
    input: [{
      background: true,
      type: "text",
      text: prompt
    }],
    response_format: [{
      type: "video",
      delivery: "inline",
      aspect_ratio: aspectRatio,
      resolution,
      duration
    }],
    generation_config: {
      video_config: {
        task: "text_to_video"
      }
    },
    store: true
  };
}
