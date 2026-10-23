// Shares the phone APK on the local network, so a phone on the same Wi-Fi can download it.
// Usage: node tools/serve-apk.mjs   (then scan the QR code shown at http://localhost:8765)
// Stop with Ctrl+C.
import http from "node:http";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

const PORT = Number(process.env.PORT || 8765);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const apk = path.join(root, "app", "build", "outputs", "apk", "release", "app-arm64-v8a-release.apk");

function lanAddress() {
  const candidates = [];
  for (const [name, addrs] of Object.entries(os.networkInterfaces())) {
    if (/vEthernet|VirtualBox|VMware|WSL|Loopback/i.test(name)) continue;
    for (const a of addrs || []) {
      if (a.family === "IPv4" && !a.internal && !a.address.startsWith("169.254.")) candidates.push(a.address);
    }
  }
  return candidates.find((ip) => ip.startsWith("192.168.")) || candidates[0] || "localhost";
}

const ip = lanAddress();
const pageUrl = `http://${ip}:${PORT}/`;

function page() {
  const stat = fs.statSync(apk);
  const mb = (stat.size / 1e6).toFixed(0);
  const built = stat.mtime.toLocaleString("fr-FR");
  return `<!doctype html>
<html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Installer Fil</title>
<style>
  :root { --bg:#fbf8f3; --ink:#1c1b19; --muted:#5e5850; --accent:#1f4e5f; --card:#ffffff; }
  @media (prefers-color-scheme: dark) { :root { --bg:#121416; --ink:#e4e2de; --muted:#b9b4ac; --accent:#8fcfe0; --card:#1c1f21; } }
  body { margin:0; background:var(--bg); color:var(--ink); font:17px/1.5 system-ui, sans-serif; }
  main { max-width:520px; margin:0 auto; padding:24px 16px 48px; }
  h1 { font-size:28px; margin:8px 0 4px; }
  .muted { color:var(--muted); font-size:15px; }
  .btn { display:block; text-align:center; background:var(--accent); color:var(--bg); text-decoration:none;
         font-weight:600; padding:16px; border-radius:999px; margin:20px 0; font-size:18px; }
  .card { background:var(--card); border-radius:16px; padding:16px 20px; margin:16px 0; }
  ol { padding-left:20px; margin:8px 0; } li { margin:6px 0; }
  #qr { display:flex; justify-content:center; padding:16px; background:#fff; border-radius:16px; }
  .pc-only { display:none; }
  @media (min-width: 700px) { .pc-only { display:block; } }
</style></head>
<body><main>
  <h1>Installer Fil</h1>
  <p class="muted">Lecteur RSS · traduction et résumés sur le téléphone · ${mb} Mo · compilé le ${built}</p>

  <div class="pc-only card">
    <p><strong>Sur ton téléphone</strong> (connecté au même Wi-Fi que ce PC), scanne ce code avec l'appareil photo :</p>
    <div id="qr"></div>
    <p class="muted" style="text-align:center">ou tape : <strong>${pageUrl}</strong></p>
  </div>

  <a class="btn" href="/Fil.apk">Télécharger Fil (${mb} Mo)</a>

  <div class="card">
    <strong>Ensuite, sur le téléphone :</strong>
    <ol>
      <li>Ouvre le fichier <em>Fil.apk</em> téléchargé (notification ou dossier Téléchargements).</li>
      <li>Si Android bloque : touche <em>Paramètres</em> et autorise ton navigateur à « installer des applis inconnues », puis reviens.</li>
      <li>Si Play Protect prévient que l'appli est inconnue : <em>Plus de détails › Installer quand même</em>. C'est normal pour une appli qui ne vient pas du Play Store.</li>
      <li>Ouvre Fil, puis importe ton fichier OPML ou ajoute une source.</li>
    </ol>
  </div>
  <p class="muted">Pour une nouvelle version, reviens sur cette page et réinstalle par-dessus : tes abonnements sont gardés.</p>
</main>
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
<script>
  if (window.QRCode && window.matchMedia("(min-width: 700px)").matches) {
    new QRCode(document.getElementById("qr"), { text: ${JSON.stringify(pageUrl)}, width: 220, height: 220 });
  }
</script>
</body></html>`;
}

const server = http.createServer((req, res) => {
  const url = (req.url || "/").split("?")[0];
  const who = req.socket.remoteAddress;
  if (url === "/Fil.apk") {
    if (!fs.existsSync(apk)) {
      res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("APK introuvable. Lance d'abord : .\\build.ps1 assembleRelease");
      return;
    }
    const size = fs.statSync(apk).size;
    console.log(`${new Date().toLocaleTimeString("fr-FR")}  téléchargement de l'APK par ${who}`);
    res.writeHead(200, {
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Length": size,
      "Content-Disposition": 'attachment; filename="Fil.apk"',
      "Cache-Control": "no-store",
    });
    fs.createReadStream(apk).pipe(res);
    return;
  }
  if (url === "/" || url === "/index.html") {
    if (!fs.existsSync(apk)) {
      res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("APK introuvable. Lance d'abord : .\\build.ps1 assembleRelease");
      return;
    }
    console.log(`${new Date().toLocaleTimeString("fr-FR")}  page ouverte par ${who}`);
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8", "Cache-Control": "no-store" });
    res.end(page());
    return;
  }
  res.writeHead(404);
  res.end();
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`APK partagé sur le réseau local.`);
  console.log(`  Sur ce PC (QR code) : http://localhost:${PORT}/`);
  console.log(`  Sur le téléphone    : ${pageUrl}`);
  console.log(`Ctrl+C pour arrêter.`);
});
