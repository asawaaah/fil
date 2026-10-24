# Fil

Lecteur RSS pour Android, inspiré de Feeeed. Il traduit en français et résume les articles **sur le téléphone**, sans compte et sans serveur.

- **Sources** : sites et blogs (RSS 2.0, RSS 1.0, Atom), détection automatique du flux depuis l'adresse d'un site, subreddits (`r/nom`).
- **Import OPML** : depuis le sélecteur de fichiers, « Ouvrir avec » ou « Partager ». Le parseur tolère les fichiers abîmés. Les dossiers sont recréés et les doublons ignorés. L'export OPML se trouve dans l'onglet Sources.
- **Réparation des vieilles listes** : les adresses `http://` sont essayées en `https://`. Un flux déplacé est retrouvé sur la page du site, et les sites qui refusent les lecteurs RSS sont réessayés sous une autre identité. Les échecs sont classés en *disparues* (supprimables d'un coup), *bloquées* et *indisponibles* (réessayées à chaque actualisation). Une source déplacée vers un autre domaine est marquée « ✎ à vérifier ». Sur une vraie liste de 401 sources datant de 2024, 305 fonctionnent.
- **Fil regroupé** : le fil est découpé par jour (« Aujourd'hui », « Hier »…). Quand une source publie au moins 3 articles le même jour, ils forment une pile : le dernier article, deux titres, et « +N autres » pour déplier sur place. Un bouton ✓ marque toute la pile comme lue (avec « Annuler »). Désactivable dans Réglages › Fil.
- **Affichage compact** (Réglages › Fil › Affichage) : titre sur deux lignes et petite image, sans extrait.
- **Recherche dans l'article** : loupe dans la barre du lecteur, sans tenir compte des majuscules ni des accents, avec surlignage et ↑ ↓.
- **Fiche source** : la même partout (appui long sur un article › « Source : … », nom de la source en haut d'un article, en-tête d'une pile dépliée, ⋮ dans Sources). Notifications, dossier, masquer, renommer, supprimer.
- **Notifications par source** : à chaque actualisation automatique, un avis par nouvel article des sources suivies (regroupé au-delà de 5 articles). Un toucher ouvre l'article.
- **Actions en lot** dans Sources : appui long pour sélectionner (case sur un dossier = tout le dossier), puis déplacer ou supprimer.
- **Écouter un article** : voix du téléphone (hors ligne), paragraphe lu surligné et suivi, mini-lecteur en verre (⏮ ⏯ ⏭, vitesse 0,8 à 2×). La lecture continue écran éteint, avec les commandes dans la notification et sur l'écran de verrouillage.
- **Qui possède les médias** : pour les médias français connus, une étiquette « Propriétaire : … » dans l'article et la fiche source, avec la chaîne de contrôle et les parts. Données incluses dans l'app (aucun appel réseau), tirées de [« Médias français, qui possède quoi »](https://github.com/mdiplo/Medias_francais) (Le Monde diplomatique et Acrimed, licence ODC-By) et de [« Qui possède les médias »](https://github.com/DesignandHuman/qui-possede-les-medias) (Design & Human). Mise à jour : `node tools/build-owners.mjs`.
- **Mode lecture** : texte complet récupéré sur le site (extracteur maison inspiré de Readability), taille et police réglables.
- **Traduction** : ML Kit, hors ligne. Langue détectée sur l'appareil, titres et extraits traduits dans le fil, article traduit à l'ouverture, bouton « voir l'original ». En option, **« Améliorer avec l'IA »** fait retraduire l'article par le modèle de résumé, partie par partie (bien plus naturel, environ 2 minutes par article). Cette traduction peut être automatique (réglage « Traduire les articles avec l'IA »).
- **Modèles** : Gemma 4 E2B (recommandé), Qwen3 1.7B, et sur Pixel 10 une version de Gemma compilée pour la puce **Tensor G5** (NPU, expérimental, avec retour automatique au modèle normal). Téléchargement en Wi-Fi ou avec les données mobiles, import d'un fichier, ou **import de n'importe quel modèle `.litertlm`**. Les formats GGUF et safetensors ne sont pas lus par LiteRT-LM. Le modèle se précharge à l'ouverture d'un article, et la durée de chaque résumé est affichée.
- **Résumé** : LiteRT-LM avec Gemma 4 E2B (2,5 Go), Gemma 4 E4B (3,7 Go, plus fin et plus lent) ou Qwen3 1.7B (1 Go), téléchargé une fois en Wi-Fi (reprise automatique si la connexion coupe). Le résumé est en français si « Traduire automatiquement » est activé, sinon dans la langue de l'article avec un bouton « Traduire le résumé ». Les résumés sont préparés à l'avance pendant la recharge. Le modèle tourne sur le processeur par défaut. Le GPU est une option expérimentale : sur l'émulateur, son démarrage ne finissait pas, donc l'app le désactive d'elle-même s'il met plus de 45 s.
- **Interface « Liquid Glass »** : barre du haut et barre d'onglets flottantes en verre (flou, réfraction sur les bords, avec la bibliothèque [Kyant Backdrop](https://github.com/Kyant0/AndroidLiquidGlass)). Le verre garde au moins 4,5:1 de contraste pour le texte. Réglage « Barres flottantes › Opaques » pour des barres pleines, appliqué d'office si le texte à contraste élevé d'Android est activé. Testé en clair, en sombre et avec le texte système à 200 %. Sur Android 11 et moins (pas de flou), les barres sont presque opaques.
- **Vie privée** : pas de compte ni de serveur. Les statistiques d'usage que ML Kit envoie normalement à Google sont coupées (voir `AndroidManifest.xml`). Seuls les flux, les pages d'articles et les fichiers de modèles et de langues sont téléchargés.

Mesuré sur l'émulateur (Ryzen 7 5800X) : résumé d'un article anglais en 37 s au total. Compter plutôt 30 à 60 s sur un téléphone milieu de gamme, d'où la préparation pendant la recharge.

## Compiler

Il faut Android Studio (pour son JDK et le SDK Android).

```powershell
.\build.ps1 assembleDebug        # APK de test : app\build\outputs\apk\debug\app-<abi>-debug.apk
.\build.ps1 assembleRelease      # APK optimisés : app\build\outputs\apk\release\app-arm64-v8a-release.apk (téléphones, ~24 Mo)
                                 #                 et app-x86_64-release.apk (émulateur du PC)
.\build.ps1 testDebugUnitTest    # tests (parsing RSS/Atom/OPML, dates, extraction)
```

Icône : `python tools/make-icon.py <logo-transparent.png>` régénère les calques de l'icône adaptative (logo centré dans le cercle visible, version monochrome pour les icônes à thème).

Vérification avec de vrais sites (réseau requis) :

```powershell
$env:FIL_LIVE = "1"; .\build.ps1 testDebugUnitTest --tests "*LiveFeedsCheck*"
```

## Installer sur le téléphone

**Sans câble, par le Wi-Fi** (le téléphone doit être sur le même réseau que le PC) :

1. `node tools\serve-apk.mjs`, puis ouvrir http://localhost:8765 sur le PC.
2. Scanner le QR code avec le téléphone, ou taper l'adresse affichée (ex. `http://192.168.0.124:8765`).
3. Télécharger, ouvrir `Fil.apk`, autoriser le navigateur à « installer des applis inconnues ».

La première fois, Windows peut demander d'autoriser Node.js sur les réseaux privés : il faut accepter, sinon le téléphone ne peut pas se connecter. Ctrl+C arrête le partage.

**Avec un câble USB** :

1. Sur le téléphone : Paramètres › À propos › appuyer 7 fois sur « Numéro de build », puis Options pour les développeurs › activer « Débogage USB ».
2. Brancher le téléphone en USB et accepter l'empreinte de l'ordinateur.
3. `adb install -r app\build\outputs\apk\release\app-arm64-v8a-release.apk`. `adb` se trouve dans `%LOCALAPPDATA%\Android\Sdk\platform-tools`.

## Organisation du code

```
app/src/main/java/com/asawaaah/fil/
  data/db/        Room : sources, articles, dossiers, filtres
  data/feeds/     lecture des flux, OPML, Reddit, découverte, import
  data/extract/   extraction d'article et rendu en blocs
  ai/             détection de langue, traduction, modèles, résumé
  work/           actualisation et préparation des résumés en tâche de fond
  ui/             écrans Compose (fil, lecture, sources, import, réglages)
```
