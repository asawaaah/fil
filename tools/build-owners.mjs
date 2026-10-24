// Builds app/src/main/assets/media_owners.tsv: who owns which French media, by web domain.
//
// Sources (downloaded here, on the PC: the app itself never goes online for this):
//  - "Médias français, qui possède quoi", Le Monde diplomatique & Acrimed
//    https://github.com/mdiplo/Medias_francais (licence ODC-By: attribution required)
//    -> media, owners and ownership chains (people, companies, shares).
//  - "Qui possède les médias", Design & Human (MIT, archived 2021)
//    https://github.com/DesignandHuman/qui-possede-les-medias
//    -> web domains of the media; its owners are only used when Le Monde diplomatique
//       doesn't know the media.
//
// Usage: node tools/build-owners.mjs

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "..");
const out = path.join(root, "app", "src", "main", "assets", "media_owners.tsv");
const MD = "https://raw.githubusercontent.com/mdiplo/Medias_francais/master/";
const QPM = "https://raw.githubusercontent.com/DesignandHuman/qui-possede-les-medias/main/data/";

async function get(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`${url}: HTTP ${res.status}`);
  return (await res.text()).replace(/^﻿/, "");
}

function tsv(text) {
  const [head, ...rows] = text.split(/\r?\n/).filter((l) => l.trim());
  const cols = head.split("\t");
  return rows.map((r) => Object.fromEntries(r.split("\t").map((v, i) => [cols[i], (v ?? "").trim()])));
}

/** Letters and digits only, no accents: "L’Express" -> "lexpress". */
const key = (s) => s.normalize("NFD").replace(/[̀-ͯ]/g, "").toLowerCase().replace(/[^a-z0-9]/g, "");

/** Web addresses that don't follow from the media's name. */
const ALIASES = {
  "Le Journal du Dimanche": ["lejdd.fr"],
  "France 2": ["francetvinfo.fr", "france.tv"],
  "France 3": ["france3-regions.francetvinfo.fr"],
  "France Info": ["francetvinfo.fr", "franceinfo.fr"],
  "France Inter": ["radiofrance.fr", "franceinter.fr"],
  "France Culture": ["franceculture.fr"],
  "France 24": ["france24.com"],
  "RFI": ["rfi.fr"],
  "Arte": ["arte.tv"],
  "TF1": ["tf1.fr", "tf1info.fr"],
  "M6": ["6play.fr", "m6.fr"],
  "Le Monde diplomatique": ["monde-diplomatique.fr"],
  "Courrier international": ["courrierinternational.com"],
  "Le Nouvel Obs": ["nouvelobs.com"],
  "L’Obs": ["nouvelobs.com"],
  "Les Inrockuptibles": ["lesinrocks.com"],
  "Le Huffington Post": ["huffingtonpost.fr"],
  "Paris Match": ["parismatch.com"],
  "Valeurs actuelles": ["valeursactuelles.com"],
  "Marianne": ["marianne.net"],
  "Mediapart": ["mediapart.fr"],
  "Ouest-France": ["ouest-france.fr"],
  "Le Dauphiné libéré": ["ledauphine.com"],
  "La Voix du Nord": ["lavoixdunord.fr"],
  "Le Progrès": ["leprogres.fr"],
  "Sud Ouest": ["sudouest.fr"],
  "La Dépêche du Midi": ["ladepeche.fr"],
  "Le Télégramme": ["letelegramme.fr"],
  "BFM TV": ["bfmtv.com"],
  "RMC": ["rmc.bfmtv.com"],
  "CNews": ["cnews.fr"],
  "Europe 1": ["europe1.fr"],
  "RTL": ["rtl.fr"],
  "Télérama": ["telerama.fr"],
  "Challenges": ["challenges.fr"],
  "Les Échos": ["lesechos.fr"],
  "Le Parisien": ["leparisien.fr"],
};

const [medias, persons, orgs, orgMedia, persMedia, orgOrg, persOrg, qpmSites] = await Promise.all([
  get(MD + "medias.tsv"), get(MD + "personnes.tsv"), get(MD + "organisations.tsv"),
  get(MD + "organisation-media.tsv"), get(MD + "personne-media.tsv"),
  get(MD + "organisation-organisation.tsv"), get(MD + "personne-organisation.tsv"),
  get(QPM + "sites.csv"),
]);

const people = new Set(tsv(persons).map((p) => p.Nom));
const mediaRows = tsv(medias).filter((m) => m.Nom && !m.Disparu);

// Who owns what: target -> [{ owner, share }]
const ownersOf = new Map();
for (const e of [...tsv(orgMedia), ...tsv(persMedia), ...tsv(orgOrg), ...tsv(persOrg)]) {
  if (!e.origine || !e.cible) continue;
  const share = e.valeur ? e.valeur.replace(".00", "").replace(".", ",").replace("%", " %") : e.qualificatif === "contrôle" ? "contrôle" : "";
  if (!ownersOf.has(e.cible)) ownersOf.set(e.cible, []);
  ownersOf.get(e.cible).push({ owner: e.origine, share });
}

/** Every chain from a final owner down to [name]: "Xavier Niel (100 %) › NJJ Presse (…) › Le Monde". */
function chains(name, seen = new Set()) {
  const parents = ownersOf.get(name) ?? [];
  if (parents.length === 0 || seen.has(name)) return [[{ name }]];
  seen = new Set(seen).add(name);
  const out = [];
  for (const p of parents) {
    for (const c of chains(p.owner, seen)) out.push([...c, { name, share: p.share }]);
  }
  return out;
}

const domains = new Map(); // domain -> media name
const qpmOwners = new Map(); // domain -> [owners]
for (const line of qpmSites.split(/\r?\n/).slice(1)) {
  const [domain, ...holders] = line.split(",").map((s) => s.trim());
  if (!domain) continue;
  qpmOwners.set(domain, holders.filter(Boolean));
}

const lines = [
  "# Propriétaires des médias français, par domaine web. Généré par tools/build-owners.mjs.",
  `# Données : « Médias français, qui possède quoi », Le Monde diplomatique et Acrimed (ODC-By), ${new Date().toISOString().slice(0, 10)} ; domaines : « Qui possède les médias », Design & Human (MIT).`,
];
const usedDomains = new Set();
const byKey = new Map(mediaRows.map((m) => [key(m.Nom), m.Nom]));
for (const m of mediaRows) {
  const all = chains(m.Nom).filter((c) => c.length > 1);
  if (all.length === 0) continue;
  // Effective stake of a chain: product of the shares ("contrôle" or unknown counts as 100 %).
  const stake = (c) => c.slice(1).reduce((acc, step) => {
    const n = parseFloat((step.share ?? "").replace(",", "."));
    return acc * (Number.isFinite(n) ? n / 100 : 1);
  }, 1);
  const best = new Map();
  for (const c of all) best.set(c[0].name, Math.max(best.get(c[0].name) ?? 0, stake(c)));
  // Biggest owner first; people before companies when equal.
  const finals = [...best.keys()].sort((a, b) => best.get(b) - best.get(a) || (people.has(b) ? 1 : 0) - (people.has(a) ? 1 : 0));
  const texts = all
    .sort((a, b) => finals.indexOf(a[0].name) - finals.indexOf(b[0].name) || stake(b) - stake(a))
    .map((c) => c.map((step, i) => (i === 0 ? step.name : `${step.share ? `(${step.share}) ` : ""}${step.name}`)).join(" › "));
  // The source lists some links twice.
  const chainText = [...new Set(texts)].slice(0, 8);
  const ds = new Set(ALIASES[m.Nom] ?? []);
  for (const d of qpmOwners.keys()) {
    const label = d.split(".").slice(-2, -1)[0] ?? "";
    if (key(label) === key(m.Nom)) ds.add(d);
  }
  if (ds.size === 0 && key(m.Nom).length >= 4 && key(m.Nom).length <= 20) ds.add(`${key(m.Nom)}.fr`);
  const free = [...ds].filter((d) => !usedDomains.has(d));
  free.forEach((d) => usedDomains.add(d));
  if (free.length === 0) continue;
  const kinds = finals.map((f) => (people.has(f) ? "P" : "O"));
  lines.push(["M", m.Nom, finals.map((f, i) => `${kinds[i]}:${f}`).join("|"), chainText.join("|"), free.join(","), "mdiplo"].join("\t"));
}
// Domains only known by the 2021 list.
let fallback = 0;
for (const [d, holders] of qpmOwners) {
  if (usedDomains.has(d) || holders.length === 0) continue;
  const label = d.replace(/^www\./, "");
  lines.push(["M", label, holders.map((h) => `?:${h}`).join("|"), "", d, "qpm2021"].join("\t"));
  usedDomains.add(d);
  fallback++;
}

fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, lines.join("\n") + "\n", "utf8");
console.log(`${lines.length - 2} médias (${fallback} seulement dans la liste de 2021), ${usedDomains.size} domaines -> ${path.relative(root, out)}`);
