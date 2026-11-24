import { base64UrlEncode, privateKeyPemToBytes } from "./google-auth.ts";

Deno.test("base64url encoding replaces URL-unsafe characters and removes padding", () => {
  const encoded = base64UrlEncode(new Uint8Array([251, 255, 255]));
  if (encoded !== "-___") throw new Error("Unexpected base64url output: " + encoded);
});

Deno.test("PKCS#8 PEM parser accepts newline-escaped service-account keys", () => {
  const der = privateKeyPemToBytes("-----BEGIN PRIVATE KEY-----\\nAQID\\n-----END PRIVATE KEY-----");
  if (der.length !== 3 || der[0] !== 1 || der[1] !== 2 || der[2] !== 3) {
    throw new Error("PEM parser did not decode the expected DER bytes.");
  }
});

Deno.test("PKCS#8 PEM parser rejects malformed key data", () => {
  let rejected = false;
  try { privateKeyPemToBytes("not a private key"); } catch { rejected = true; }
  if (!rejected) throw new Error("Malformed private key was accepted.");
});

Deno.test("decoded PKCS#8 key imports and signs an RS256 JWT payload", async () => {
  const generated = await crypto.subtle.generateKey(
    { name: "RSASSA-PKCS1-v1_5", modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: "SHA-256" },
    true,
    ["sign", "verify"]
  );
  const der = new Uint8Array(await crypto.subtle.exportKey("pkcs8", generated.privateKey));
  let binary = "";
  for (const byte of der) binary += String.fromCharCode(byte);
  const pem = "-----BEGIN PRIVATE KEY-----\\n" + btoa(binary) + "\\n-----END PRIVATE KEY-----";
  const parsed = privateKeyPemToBytes(pem);
  const buffer = new ArrayBuffer(parsed.byteLength);
  new Uint8Array(buffer).set(parsed);
  const imported = await crypto.subtle.importKey(
    "pkcs8",
    buffer,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const message = new TextEncoder().encode("new-star-vertex-auth-test");
  const signature = await crypto.subtle.sign("RSASSA-PKCS1-v1_5", imported, message);
  const valid = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", generated.publicKey, signature, message);
  if (!valid) throw new Error("Imported PKCS#8 key did not produce a valid RS256 signature.");
});
