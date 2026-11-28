export function isValidSfuMid(value: unknown): boolean {
  return /^\d+$/.test(String(value ?? ""));
}
