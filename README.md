# HotRelay

Application Android native de partage de fichiers **sans Internet**, entre plusieurs téléphones connectés au même hotspot Wi-Fi.

Un téléphone active son point d'accès et lance HotRelay en mode **hôte** : il sert de relais. Les autres le rejoignent en mode **client** et s'envoient des fichiers à travers lui.

```
Client expéditeur ──UDP──▶ Hôte (relais) ──UDP──▶ Client destinataire
```

## Fonctionnalités (MVP)

- Créer une session (hôte) ou la rejoindre (client) — jusqu'à 8 participants.
- Voir en direct la liste des participants connectés.
- Choisir un fichier et un destinataire, suivre la progression.
- Transfert fiable sur UDP : blocs numérotés, accusés de réception, retransmissions, vérification SHA-256.
- Journal local des transferts.

Détails : [`docs/specification/FUNCTIONAL_REQUIREMENTS.md`](docs/specification/FUNCTIONAL_REQUIREMENTS.md).

## Protocole

Les téléphones parlent **HRP** (HotRelay Protocol) sur UDP, port **50000**.
La spécification complète — format des 14 messages, retransmissions, erreurs — est dans [`docs/protocole.md`](docs/protocole.md) ([version PDF](docs/HRP_HotRelay_Protocol_v1.0.pdf)).

**C'est la référence** : en cas de différence entre une issue et le protocole, le protocole a raison.

## Technologies

| | |
|---|---|
| Langage | Java (cible Java 11 dans `app/build.gradle.kts`) |
| Interface | Vues XML, Activities / Fragments, RecyclerView |
| Réseau | `DatagramSocket` (UDP), aucune bibliothèque externe |
| Android | `minSdk 26` (Android 8.0) · `compileSdk` / `targetSdk 37` |
| IDE | Android Studio |

## Structure du projet

```
app/src/main/java/com/example/hotrelay/
├── protocol/   messages HRP : lecture / écriture des paquets
├── network/    socket UDP, adresses IP
├── session/    rôle hôte / client, participants
├── transfer/   découpage en blocs, NICE, retransmissions
├── files/      lecture et enregistrement des fichiers Android
└── ui/         écrans (Activities, Fragments, adapters)
docs/           protocole, architecture, spécification
```

Vue d'ensemble : [`docs/architecture/OVERVIEW.md`](docs/architecture/OVERVIEW.md).

## Compiler et installer

1. Cloner le dépôt et ouvrir le dossier racine dans **Android Studio**.
2. Attendre la synchronisation Gradle.
3. Sur le téléphone : activer le **mode développeur** puis le **débogage USB**, et le brancher.
4. Cliquer sur ▶ **Run**.

En ligne de commande :

```bash
./gradlew assembleDebug        # APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew test                 # tests unitaires
```

Les logs de l'application se lisent dans **Logcat** (Android Studio, en bas).

## Tester à plusieurs

1. Téléphone A : activer le **point d'accès Wi-Fi**, lancer HotRelay en mode hôte.
2. Téléphones B, C… : se connecter au Wi-Fi de A, lancer HotRelay et rejoindre la session.
3. Envoyer un fichier de B vers C : il passe par A.

## Équipe

| Pôle | Membre (GitHub) | Responsabilité |
|---|---|---|
| Réseau | `nkoumougrinnel` | Protocole, UDP, session hôte / client, relais |
| Fichiers | `hassimatouhadja4-droid` | Découpage en blocs, NICE, retransmissions, enregistrement |
| Interface | `kk64q8bjmf-a11y` | Écrans, navigation, affichage des états |

## Contribuer

Une branche par issue, une Pull Request relue par un autre membre : voir [`CONTRIBUTING.md`](CONTRIBUTING.md).
