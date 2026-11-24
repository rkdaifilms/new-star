import { assertEquals } from "jsr:@std/assert";
import { detectReplyScript } from "./language-policy.ts";

Deno.test("detects Bengali script and gives Bengali reply guidance", () => {
  const result = detectReplyScript("আপনি কেমন আছেন? আমি ভালো আছি।");
  assertEquals(result?.script, "Bengali");
  assertEquals(result?.confidence, "high");
  if (result) assertEquals(result.instruction.includes("Reply in Bengali"), true);
});

Deno.test("detects Devanagari without assuming Hindi instead of Nepali", () => {
  const result = detectReplyScript("मलाई नेपाली भाषामा उत्तर दिनुहोस्।");
  assertEquals(result?.script, "Devanagari");
  assertEquals(result?.confidence, "low");
  if (result) assertEquals(result.instruction.includes("Do not assume Hindi"), true);
});

Deno.test("detects Japanese kana", () => {
  const result = detectReplyScript("こんにちは、元気ですか。");
  assertEquals(result?.script, "Hiragana/Katakana");
});

Deno.test("does not infer a language from a short or mixed-script message", () => {
  assertEquals(detectReplyScript("OK नमस्ते"), null);
  assertEquals(detectReplyScript("Hi!"), null);
});

Deno.test("treats Arabic script as language-ambiguous", () => {
  const result = detectReplyScript("یہ ایک اردو زبان کا جملہ ہے");
  assertEquals(result?.script, "Arabic");
  assertEquals(result?.confidence, "low");
  if (result) assertEquals(result.instruction.includes("Do not assume Arabic"), true);
});
