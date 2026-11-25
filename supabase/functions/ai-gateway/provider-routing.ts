export type ChatMessageLike = { role: "user" | "assistant"; content: string };
export type ChatProvider = {
  name: string;
  keys: string[];
  run: (key: string, messages: ChatMessageLike[], systemPrompt: string) => Promise<string>;
};

export type ChatProviderPlanOptions = {
  selfHostedOnly: boolean;
  newStarConfigured: boolean;
  newStarApiPrimary: boolean;
  enableLocalTestFallback: boolean;
  newStarModelKey: string;
  geminiKeys: string[];
  openaiKeys: string[];
  openrouterKeys: string[];
  runNewStarHostedModel: ChatProvider["run"];
  runGemini: ChatProvider["run"];
  runOpenAI: ChatProvider["run"];
  runOpenRouter: ChatProvider["run"];
};

export function buildChatProviderPlan(options: ChatProviderPlanOptions): ChatProvider[] {
  const privateProvider: ChatProvider = {
    name: "new-star-ai",
    keys: [options.newStarModelKey],
    run: options.runNewStarHostedModel
  };

  if (options.selfHostedOnly) {
    return options.newStarConfigured && options.newStarModelKey.length >= 32
      ? [privateProvider]
      : [];
  }

  const providers: ChatProvider[] = [
    ...(options.newStarApiPrimary && options.newStarConfigured && options.newStarModelKey.length >= 32
      ? [privateProvider]
      : []),
    { name: "gemini", keys: options.geminiKeys, run: options.runGemini },
    { name: "openai", keys: options.openaiKeys, run: options.runOpenAI },
    { name: "openrouter", keys: options.openrouterKeys, run: options.runOpenRouter },
    ...(!options.newStarApiPrimary && options.enableLocalTestFallback && options.newStarConfigured && options.newStarModelKey.length >= 32
      ? [{ ...privateProvider, name: "new-star-ai-fallback" }]
      : [])
  ];
  return providers.filter((provider) => provider.keys.length > 0);
}
