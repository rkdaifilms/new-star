export function base64UrlEncode(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/=/g, "").replace(/\+/g, "-").replace(/\//g, "_");
}

export function privateKeyPemToBytes(pem: string): Uint8Array {
  const normalized = pem.replace(/\\n/g, "\n").trim();
  if (!normalized.startsWith("-----BEGIN PRIVATE KEY-----") || !normalized.endsWith("-----END PRIVATE KEY-----")) {
    throw new Error("Google service-account private key is not valid PKCS#8 PEM.");
  }
  const body = normalized.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  if (!body || !/^[A-Za-z0-9+/]+={0,2}$/.test(body)) {
    throw new Error("Google service-account private key is not valid PKCS#8 PEM.");
  }
  return Uint8Array.from(atob(body), (character) => character.charCodeAt(0));
}
