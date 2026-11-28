import { isValidSfuMid } from "./sfu-validation.ts";

Deno.test("accepts numeric SFU media MIDs", () => {
  for (const value of ["0", "1", "27", 3]) {
    if (!isValidSfuMid(value)) throw new Error(`Expected valid MID: ${String(value)}`);
  }
});

Deno.test("rejects malformed SFU media MIDs", () => {
  for (const value of ["", " ", "-1", "1a", "\\d", "1.0", null, undefined]) {
    if (isValidSfuMid(value)) throw new Error(`Expected invalid MID: ${String(value)}`);
  }
});
