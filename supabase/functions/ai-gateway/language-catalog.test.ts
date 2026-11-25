import { AI_LANGUAGE_NAMES } from "./language-catalog.ts";

Deno.test("language catalog includes major regional and global languages", () => {
  const required = ["en","hi","ne","bn","ur","pa","gu","mr","ta","te","kn","ml","si","ar","fa","zh","ja","ko","th","sw","am","yo","zu","uk","ru","el"];
  for (const code of required) {
    if (!AI_LANGUAGE_NAMES[code]) throw new Error("Missing language code: " + code);
  }
});

Deno.test("language names are non-empty and catalog codes are normalized", () => {
  for (const [code, name] of Object.entries(AI_LANGUAGE_NAMES)) {
    if (!/^[a-z]{2,3}$/.test(code)) throw new Error("Invalid language code: " + code);
    if (!name.trim()) throw new Error("Empty language name for: " + code);
  }
});
