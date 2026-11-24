import { detectReplyScript } from "./language-policy.ts";

Deno.test("detects Bengali script and gives Bengali reply guidance", () => {
  const result = detectReplyScript("আপনি কেমন আছেন? আমি ভালো আছি।");
  if (result?.script !== "Bengali") throw new Error("Bengali script was not detected");
  if (result?.confidence !== "high") throw new Error("Bengali confidence should be high");
  if (result && !result.instruction.includes("Reply in Bengali")) throw new Error("Bengali reply guidance missing");
});

Deno.test("detects Devanagari without assuming Hindi instead of Nepali", () => {
  const result = detectReplyScript("मलाई नेपाली भाषामा उत्तर दिनुहोस्।");
  if (result?.script !== "Devanagari") throw new Error("Devanagari script was not detected");
  if (result?.confidence !== "low") throw new Error("Devanagari must remain ambiguous");
  if (result && !result.instruction.includes("Do not assume Hindi")) throw new Error("Hindi/Nepali ambiguity warning missing");
});

Deno.test("detects Japanese kana", () => {
  const result = detectReplyScript("こんにちは、元気ですか。");
  if (result?.script !== "Hiragana/Katakana") throw new Error("Japanese kana was not detected");
});

Deno.test("does not infer a language from a short or mixed-script message", () => {
  if (detectReplyScript("OK नमस्ते") !== null) throw new Error("Mixed short message should remain ambiguous");
  if (detectReplyScript("Hi!") !== null) throw new Error("Short Latin message should remain ambiguous");
});

Deno.test("treats Arabic script as language-ambiguous", () => {
  const result = detectReplyScript("یہ ایک اردو زبان کا جملہ ہے");
  if (result?.script !== "Arabic") throw new Error("Arabic script was not detected");
  assertEquals(result?.confidence, "low");
  if (result && !result.instruction.includes("Do not assume Arabic")) throw new Error("Arabic/Urdu/Persian ambiguity warning missing");
});
