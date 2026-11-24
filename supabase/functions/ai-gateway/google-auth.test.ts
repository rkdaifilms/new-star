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
