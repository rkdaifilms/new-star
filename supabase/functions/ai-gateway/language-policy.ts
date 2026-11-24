/** Conservative script detection for reply-language guidance.
 * Script is not the same as language: Devanagari, Arabic, Han, Latin and
 * Cyrillic are shared by multiple languages. Ambiguous scripts are labeled
 * honestly so the model uses lexical context instead of guessing.
 */
export type ScriptHint = {
  script: string;
  instruction: string;
  confidence: "high" | "medium" | "low";
};

const SCRIPT_PATTERNS: Array<{ name: string; pattern: RegExp }> = [
  { name: "Devanagari", pattern: /\p{Script=Devanagari}/u },
  { name: "Bengali", pattern: /\p{Script=Bengali}/u },
  { name: "Arabic", pattern: /\p{Script=Arabic}/u },
  { name: "Han", pattern: /\p{Script=Han}/u },
  { name: "Hiragana/Katakana", pattern: /[\p{Script=Hiragana}\p{Script=Katakana}]/u },
  { name: "Hangul", pattern: /\p{Script=Hangul}/u },
  { name: "Thai", pattern: /\p{Script=Thai}/u },
  { name: "Latin", pattern: /\p{Script=Latin}/u },
  { name: "Cyrillic", pattern: /\p{Script=Cyrillic}/u },
];

export function detectReplyScript(text: string): ScriptHint | null {
  const counts = new Map<string, number>();
  for (const char of text) {
    if (/\p{Letter}/u.test(char) === false) continue;
    const match = SCRIPT_PATTERNS.find((entry) => entry.pattern.test(char));
    if (match) counts.set(match.name, (counts.get(match.name) || 0) + 1);
  }
  const ranked = [...counts.entries()].sort((a, b) => b[1] - a[1]);
  const [dominant, count] = ranked[0] || [];
  if (!dominant || count < 2) return null;
  const total = ranked.reduce((sum, item) => sum + item[1], 0);
  if (count / total < 0.6) return null;

  const ambiguous = new Set(["Devanagari", "Arabic", "Han", "Latin", "Cyrillic"]);
  const confidence = ambiguous.has(dominant) ? "low" : "high";
  const instruction = dominant === "Devanagari"
    ? "The latest user message is predominantly in Devanagari script. Reply in the same language and script; use vocabulary and context to distinguish Hindi, Nepali, or another Devanagari language. Do not assume Hindi solely from the script."
    : dominant === "Arabic"
    ? "The latest user message is predominantly in Arabic script. Reply in the same language and script; use context to distinguish Arabic, Urdu, Persian, or another Arabic-script language. Do not assume Arabic solely from the script."
    : dominant === "Han"
    ? "The latest user message is predominantly in Han characters. Use context to distinguish Chinese from Japanese and reply in the user's language."
    : dominant === "Latin"
    ? "The latest user message is predominantly in Latin script. Identify its language from the words and context, then reply in that language."
    : dominant === "Cyrillic"
    ? "The latest user message is predominantly in Cyrillic script. Identify its language from the words and context, then reply in that language."
    : dominant === "Bengali"
    ? "The latest user message is predominantly in Bengali script. Reply in Bengali using Bengali script."
    : dominant === "Hiragana/Katakana"
    ? "The latest user message contains Japanese kana. Reply in Japanese using Japanese writing."
    : dominant === "Hangul"
    ? "The latest user message is predominantly in Hangul. Reply in Korean using Hangul."
    : dominant === "Thai"
    ? "The latest user message is predominantly in Thai script. Reply in Thai."
    : "Match the language and script of the latest user message.";
  return { script: dominant, instruction, confidence };
}
