# HRP — HotRelay Protocol

**Spécification technique · version 1.0 · 12 novembre 2026**

> Le langage commun des téléphones HotRelay : rejoindre une session, connaître les participants et transférer des fichiers bloc par bloc sur UDP, sans Internet.
>
> Version mise en page avec schémas : [`HRP_HotRelay_Protocol_v1.0.pdf`](HRP_HotRelay_Protocol_v1.0.pdf)

| | |
|---|---|
| Transport | UDP, port **50000** sur l'hôte |
| En-tête commun | **8 octets**, MAGIC « HRP » |
| Types de messages | **14** |
| Données max. par bloc | **1 024 octets** |
| Participants max. | **8** (hôte + 7 clients) |
| Fiabilité | **Stop-and-Wait** : un bloc, un accusé NICE |

## Sommaire

1. [La pile réseau](#1-la-pile-réseau)
2. [Architecture : l'hôte relais](#2-architecture--lhôte-relais)
3. [Identifiants](#3-identifiants)
4. [Conventions binaires](#4-conventions-binaires)
5. [L'en-tête commun](#5-len-tête-commun)
6. [Registre des messages](#6-registre-des-messages)
7. [Détail des messages](#7-détail-des-messages)
8. [Session et participants](#8-session-et-participants)
9. [Transfert de fichiers](#9-transfert-de-fichiers)
10. [Retransmissions et délais](#10-retransmissions-et-délais)
11. [Erreurs et interruptions](#11-erreurs-et-interruptions)
12. [Robustesse et sécurité](#12-robustesse-et-sécurité)
13. [Décisions v1 et versionnage](#13-décisions-v1-et-versionnage)
- [Annexes](#annexe-a--structures-condensées)

### Ce que fait HRP

| UDP seul ne garantit pas… | …donc HRP ajoute |
|---|---|
| que le paquet arrive | des accusés de réception (NICE) et des retransmissions |
| qu'il arrive une seule fois | la détection des doublons |
| qu'il arrive dans l'ordre | des numéros de bloc (SEQ) |
| qu'on sache à qui il s'adresse | des identifiants de participants et de transferts |
| | une empreinte SHA-256 pour vérifier le fichier final |

---

## 1. La pile réseau

HRP est un **protocole applicatif** : il ne remplace ni UDP ni IP, il s'appuie dessus.

```
┌─────────────────────────────────────────────────────────────┐
│  Application HotRelay   écrans, choix du fichier, progression│
├─────────────────────────────────────────────────────────────┤
│  HRP                    session, participants, blocs, NICE,  │
│                         retransmissions, SHA-256             │
├─────────────────────────────────────────────────────────────┤
│  UDP                    datagrammes, ports (50000 sur l'hôte)│
├─────────────────────────────────────────────────────────────┤
│  IP                     adresses du réseau du hotspot        │
├─────────────────────────────────────────────────────────────┤
│  Wi-Fi 802.11           point d'accès activé sur l'hôte      │
└─────────────────────────────────────────────────────────────┘
```

Encapsulation :

```
| IP 20 o | UDP 8 o | En-tête HRP 8 o | Payload HRP 0 à 1 036 o |
```

Le plus gros message (TAKE) fait 8 + 12 + 1 024 = **1 044 octets** de données UDP, sous la limite d'environ 1 472 octets d'un réseau Wi-Fi classique : **pas de fragmentation IP**.

> UDP, c'est la poste : il transporte des cartes postales sans rien garantir. HRP, c'est ce qu'on écrit sur les cartes et les règles pour s'assurer que le fichier arrive entier.

## 2. Architecture : l'hôte relais

```
        [ID 1]          [ID 2]
            \            /
             \          /
              [ HÔTE · ID 0 ]
             /          \
            /            \
        [ID 3]          [ID 4] …
```

Un téléphone active son hotspot et lance HotRelay en mode **hôte**. Les autres rejoignent ce Wi-Fi puis la session : ce sont les **clients**. Les clients ne se parlent **jamais directement** : tout passe par l'hôte, qui lit l'en-tête, consulte sa table et relaie le datagramme.

Table de session (exemple) :

| ID | Nom | Endpoint | Dernier signe de vie |
|---|---|---|---|
| 0 | Hôte | — | — |
| 1 | Paul | 192.168.x.12:41822 | il y a 2 s |
| 2 | Aïcha | 192.168.x.87:38004 | il y a 4 s |

| Rôle | Responsabilités |
|---|---|
| **Hôte** · ID 0 | Crée la session · attribue les IDs · tient la table (nom, IP, port, dernier signe de vie) · valide et relaie les messages · diffuse SYNCTABLE · peut aussi envoyer et recevoir des fichiers |
| **Client** · ID 1 à 7 | Rejoint la session · envoie AVAILABLE · choisit un destinataire · envoie ou reçoit des blocs · reconstruit le fichier |

> **Adresse de l'hôte** — Depuis Android 10, l'IP du hotspot n'est pas toujours `192.168.43.1`. L'écran hôte affiche l'adresse réellement lue sur l'interface ; le client la saisit à la main (pas de découverte automatique en v1).

L'hôte **ne stocke pas** les fichiers : il garde seulement le routage de chaque transfert (SRC, DST, TRANSFER_ID, état). C'est l'émetteur qui conserve chaque bloc jusqu'à son NICE.

## 3. Identifiants

| Identifiant | Taille | Qui le crée | À quoi il sert |
|---|---|---|---|
| `REQUEST_ID` | 8 o | Le client, au hasard, une fois par tentative | Reconnaître une même demande NEW renvoyée plusieurs fois, **avant** que le client ait un ID |
| `ID` participant | 1 o | L'hôte, dans JOIN | 0 = hôte, 1 à 7 = clients. Jamais réutilisé dans une session |
| `TRANSFER_ID` | 4 o | L'émetteur du fichier | Désigner un transfert complet ; permet à l'hôte de router les TAKE |
| `SEQ` | 4 o | L'émetteur | Numéro d'un bloc **dans** un transfert (0, 1, 2…) |

### Construction du TRANSFER_ID

Le message TAKE ne répète ni l'émetteur ni le destinataire : l'hôte route uniquement grâce au TRANSFER_ID. Deux transferts simultanés ne doivent donc **jamais** porter le même numéro.

```
TRANSFER_ID = (ID de l'émetteur × 2^24) + compteur      compteur = 1, 2, 3…

┌──────────────┬──────────────────────────────────────┐
│ ID émetteur  │ compteur propre à l'émetteur          │
│   1 octet    │ 3 octets (1 à 16 777 215)            │
└──────────────┴──────────────────────────────────────┘

Paul  (ID 1), 1er envoi : 01 00 00 01
Aïcha (ID 2), 1er envoi : 02 00 00 01
```

- Chacun compte de son côté sans collision possible : le premier octet diffère.
- Le compteur démarrant à 1, un TRANSFER_ID n'est **jamais 0** (même pour l'hôte : `00 00 00 01`). La valeur 0 signifie « aucun transfert » dans ERROR.
- L'hôte vérifie que le premier octet correspond bien à l'émetteur.

## 4. Conventions binaires

| Type | Taille | Représentation |
|---|---|---|
| U8 | 1 o | Entier non signé 0 à 255 |
| U16 | 2 o | Non signé, big-endian |
| U32 | 4 o | Non signé, big-endian |
| U64 | 8 o | Non signé, big-endian |
| BYTES | variable | Suite d'octets brute |
| UTF-8 | variable | Texte ; longueur comptée en **octets** |

- **Big-endian** (ordre réseau) : l'octet de poids fort part en premier. 1 036 s'écrit `04 0C`.
- Une longueur de texte compte des octets : « Aïcha » fait 5 caractères mais **6 octets**.
- En Java, les entiers sont signés : lire un U8 avec `b & 0xFF`, un U16 avec `getShort() & 0xFFFF`, un U32 avec `getInt() & 0xFFFFFFFFL`.

```java
ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
```

## 5. L'en-tête commun

Tous les messages commencent par le même en-tête de **8 octets**, suivi du payload.

```
 0       1       2       3       4       5       6       7       8 …
┌───────┬───────┬───────┬───────┬───────┬───────┬───────┬───────┬──────────
│   H   │   R   │   P   │VERSION│ TYPE  │ FLAGS │    LENGTH     │ PAYLOAD…
└───────┴───────┴───────┴───────┴───────┴───────┴───────┴───────┴──────────
 \_________ MAGIC _________/                      U16 big-endian
```

| Octets | Champ | Valeur en v1 | Rôle |
|---|---|---|---|
| 0–2 | MAGIC | `48 52 50` (« HRP ») | Reconnaître un datagramme HRP et jeter le reste |
| 3 | VERSION | `01` | Une version inconnue est ignorée |
| 4 | TYPE | `01` à `0E` | Dit comment lire le payload |
| 5 | FLAGS | `00` obligatoire | Réservé. En v1, FLAGS ≠ 0 ⇒ paquet ignoré |
| 6–7 | LENGTH | 0 à 1 036 | Longueur du **payload seul** |

**Taille du datagramme = 8 + LENGTH**

### Exemple : un TAKE décodé

Paul (ID 1) envoie le bloc 0 de son premier transfert (fichier de 3 blocs). Bloc plein : 12 + 1 024 = 1 036 = `04 0C`.

```
48 52 50 | 01      | 08          | 00    | 04 0C         | 01 00 00 01  | 00 00 00 00 | 00 00 00 03 | …
MAGIC    | VERSION | TYPE = TAKE | FLAGS | LENGTH = 1036 | TRANSFER_ID  | SEQ = 0     | TOTAL = 3   | DATA (1 024 o)
```

### Validation, dans cet ordre

1. Le datagramme fait au moins **8 octets**.
2. MAGIC = `48 52 50`, VERSION = `01`, FLAGS = `00`.
3. TYPE est connu (sinon : ignorer sans lire le payload).
4. Taille réelle = **8 + LENGTH** exactement.
5. LENGTH respecte la taille minimale du TYPE ; chaque champ de longueur est vérifié avant lecture.

> **Règle d'or** — un paquet réseau est une entrée non fiable, même sur un réseau local. Un message invalide est ignoré : il ne doit **jamais** faire planter l'application.

## 6. Registre des messages

| Code | Message | Famille | Direction | Rôle |
|---|---|---|---|---|
| `0x01` | NEW | Session | Client → hôte | Demande de rejoindre la session |
| `0x02` | JOIN | Session | Hôte → client | Admission et attribution de l'ID |
| `0x03` | SYNCTABLE | Session | Hôte → tous | Liste des participants · signe de vie de l'hôte |
| `0x04` | AVAILABLE | Présence | Client → hôte | Signe de vie du client (5 s) |
| `0x05` | BYE | Présence | Client → hôte | Départ volontaire |
| `0x06` | READY | Transfert | Émetteur → hôte → dest. | Annonce d'un fichier |
| `0x07` | YES_READY | Transfert | Dest. → hôte → émetteur | Acceptation |
| `0x08` | TAKE | Transfert | Émetteur → hôte → dest. | Un bloc de données |
| `0x09` | NICE | Transfert | Dest. → hôte → émetteur | Accusé de réception d'un bloc |
| `0x0A` | FINISH | Transfert | Émetteur → hôte → dest. | Fin + empreinte SHA-256 |
| `0x0B` | COMPLETE | Transfert | Dest. → hôte → émetteur | Fichier reçu et vérifié |
| `0x0C` | ERROR | Contrôle | Selon le cas | Signalement d'une erreur |
| `0x0D` | CANCEL | Contrôle | Émetteur → hôte → dest. | Annulation d'un transfert |
| `0x0E` | EXIT | Contrôle | Hôte → tous | Fermeture de la session |

Un TYPE inconnu est ignoré sans crash.

```
ENTRER                 RESTER               TRANSFÉRER                          SORTIR
NEW → JOIN → SYNCTABLE   AVAILABLE ⇄ SYNCTABLE  READY → YES_READY → (TAKE ⇄ NICE) × TOTAL   BYE / EXIT
                                                → FINISH → COMPLETE
```

## 7. Détail des messages

Chaque structure décrit le payload placé **après** l'en-tête de 8 octets.

### 0x01 · NEW — Client → hôte
Le client demande à rejoindre la session.

| Champ | Taille | Description |
|---|---|---|
| REQUEST_ID | 8 (U64) | Aléatoire, identique pour tous les renvois de cette demande |
| NAMELENGTH | 1 | Longueur du nom en octets |
| NAME | ≤ 32 | Nom affiché du client, UTF-8 |

Session pleine (8 participants) → ERROR `SESSION_FULL`.

### 0x02 · JOIN — Hôte → client
L'hôte admet le client et lui donne son ID.

| Champ | Taille | Description |
|---|---|---|
| ID | 1 | ID attribué (1 à 7) |
| HOST_NAME_LENGTH | 1 | Longueur du nom de l'hôte |
| HOST_NAME | ≤ 32 | Nom de l'hôte, UTF-8 |
| REQUEST_ID | 8 | Écho de la demande NEW |

Un NEW répété avec le même REQUEST_ID reçoit le **même** JOIN, sans créer de second participant. À réception, le client se considère connecté.

### 0x03 · SYNCTABLE — Hôte → tous les clients

| Champ | Taille | Description |
|---|---|---|
| HOSTS | 1 | Nombre de participants, hôte compris |
| puis HOSTS fois : ID | 1 | Identifiant |
| NAMELENGTH | 1 | Longueur du nom |
| NAME | ≤ 32 | Nom, UTF-8 |

Envoyée à chaque changement **et toutes les 5 s** : elle sert aussi de signe de vie de l'hôte. Les IP et ports ne sont jamais diffusés.

### 0x04 · AVAILABLE — Client → hôte
Payload vide. Envoyé toutes les 5 s. L'hôte identifie le client par son endpoint et met à jour son dernier signe de vie. Sans nouvelles depuis 15 s, il retire le client et diffuse SYNCTABLE.

### 0x05 · BYE — Client → hôte
Payload vide. L'hôte retire le client, interrompt ses transferts et diffuse SYNCTABLE.

### 0x06 · READY — Émetteur → hôte → destinataire

| Champ | Taille | Description |
|---|---|---|
| SRC | 1 | ID de l'émetteur |
| DST | 1 | ID du destinataire |
| TRANSFER_ID | 4 | Voir §3 |
| FILE_NAME_LENGTH | 2 (U16) | Longueur du nom de fichier |
| FILE_NAME | ≤ 255 | Nom seul, sans chemin, UTF-8 |
| FILE_SIZE | 8 (U64) | Taille exacte en octets |

L'hôte vérifie : SRC = endpoint observé, DST connecté, 1er octet du TRANSFER_ID = SRC, TRANSFER_ID inédit. Puis il mémorise le routage.

### 0x07 · YES_READY — Destinataire → hôte → émetteur

| Champ | Taille |
|---|---|
| TRANSFER_ID | 4 |

Refus : ERROR `TRANSFER_REJECTED` à la place. Dans le MVP, l'application peut accepter automatiquement.

### 0x08 · TAKE — Émetteur → hôte → destinataire

| Champ | Taille | Description |
|---|---|---|
| TRANSFER_ID | 4 | Transfert concerné |
| SEQ | 4 | Numéro du bloc, à partir de 0 |
| TOTAL | 4 | Nombre total de blocs |
| DATA | 0 à 1 024 | Contenu du bloc |

Taille des données : `DATA = LENGTH − 12`. Pas de SRC/DST : l'hôte route avec le TRANSFER_ID enregistré lors de READY.

### 0x09 · NICE — Destinataire → hôte → émetteur

| Champ | Taille |
|---|---|
| TRANSFER_ID | 4 |
| SEQ | 4 |

Seul le NICE du destinataire vaut confirmation ; l'hôte n'en fabrique jamais. Un NICE dont le TRANSFER_ID ou le SEQ ne correspond pas est ignoré.

### 0x0A · FINISH — Émetteur → hôte → destinataire

| Champ | Taille |
|---|---|
| TRANSFER_ID | 4 |
| SHA256 | 32 |

SHA-256 des octets du fichier dans l'ordre. Vérifie l'intégrité, ne chiffre rien.

### 0x0B · COMPLETE — Destinataire → hôte → émetteur

| Champ | Taille |
|---|---|
| TRANSFER_ID | 4 |

Envoyé après reconstruction et vérification de l'empreinte. Empreinte différente → ERROR `HASH_MISMATCH`.

### 0x0C · ERROR — selon le contexte

| Champ | Taille | Description |
|---|---|---|
| ERROR_CODE | 2 (U16) | Voir §11 |
| TRANSFER_ID | 4 | 0 si l'erreur ne concerne aucun transfert |

### 0x0D · CANCEL — Émetteur → hôte → destinataire

| Champ | Taille |
|---|---|
| TRANSFER_ID | 4 |

Seul l'émetteur peut annuler. Pas d'accusé : envoyé 3 fois ; le recevoir plusieurs fois est sans effet.

### 0x0E · EXIT — Hôte → tous les clients
Payload vide. Envoyé 3 fois avant la fermeture du socket. Les clients arrêtent tout et suppriment leurs fichiers partiels.

## 8. Session et participants

### Connexion : 3 étapes

```
CLIENT A                                   HÔTE
   |──── NEW (REQUEST_ID, "Paul") ────────▶|
   |                                       |  dédoublonne, attribue ID 1
   |◀─── JOIN (ID 1, "Hôte", REQUEST_ID) ──|
   |  connecté                             |
   |◀─── SYNCTABLE (à tous) ───────────────|
   |          … puis toutes les 5 s …      |
   |──── AVAILABLE ───────────────────────▶|
   |◀─── SYNCTABLE ────────────────────────|
```

Si NEW ou JOIN se perd, le client renvoie NEW avec le même REQUEST_ID ; l'hôte reconnaît la demande et renvoie le même JOIN.

| État | Signification |
|---|---|
| CONNECTED | JOIN envoyé · client admis |
| DISCONNECTED | BYE reçu ou 15 s sans AVAILABLE |

### Signes de vie croisés

| Intervalle | Règle |
|---|---|
| 5 s | Le client envoie AVAILABLE |
| 5 s | L'hôte diffuse SYNCTABLE |
| 15 s | Silence ⇒ l'autre est considéré parti |

Côté client : 15 s sans aucun datagramme de l'hôte = hôte perdu. Le client interrompt ses transferts et revient à l'accueil.

### Départs

- **BYE** (un client part) : l'hôte le retire, interrompt ses transferts (l'autre partie reçoit ERROR `TRANSFER_FAILED`) et diffuse SYNCTABLE.
- **EXIT** (l'hôte ferme) : envoyé 3 fois à tous ; chaque client arrête ses transferts, supprime ses fichiers partiels et ferme son socket.

**Capacité :** 8 participants au plus. Les IDs ne sont pas réutilisés dans une session ; une nouvelle session recommence à 1.

## 9. Transfert de fichiers

### Scénario complet

```
ÉMETTEUR A               HÔTE                  DESTINATAIRE B
   |──── READY ─────────▶|──── READY ─────────▶|
   |◀─── YES_READY ──────|◀─── YES_READY ──────|
   |──── TAKE 0 ────────▶|──── TAKE 0 ────────▶|
   |◀─── NICE 0 ─────────|◀─── NICE 0 ─────────|
   |      … blocs suivants, un à la fois …     |
   |──── TAKE n ────────▶|──── TAKE n ────────▶|
   |◀─── NICE n ─────────|◀─── NICE n ─────────|
   |──── FINISH ────────▶|──── FINISH ────────▶|
   |                     |                     |  vérifie SHA-256
   |◀─── COMPLETE ───────|◀─── COMPLETE ───────|
```

### Découpage en blocs

```
rapport.pdf · 3 000 octets
┌──────────────┬──────────────┬─────────────┐
│ SEQ 0        │ SEQ 1        │ SEQ 2       │
│ 1 024 o      │ 1 024 o      │ 952 o       │
└──────────────┴──────────────┴─────────────┘

TOTAL = (FILE_SIZE + 1023) / 1024   (division entière)   SEQ va de 0 à TOTAL − 1
```

| Taille | TOTAL | Blocs |
|---|---|---|
| 0 o | 0 | aucun TAKE : FINISH directement |
| 1 o | 1 | 1 |
| 1 024 o | 1 | 1 024 |
| 1 025 o | 2 | 1 024 + 1 |
| 3 000 o | 3 | 1 024 + 1 024 + 952 |

### Stop-and-Wait : un bloc à la fois

L'émetteur envoie un bloc, puis **attend son NICE** avant le suivant. C'est plus lent qu'une fenêtre glissante, mais très simple : un seul bloc en mémoire et les blocs arrivent forcément dans l'ordre. Le destinataire écrit le fichier au fil de l'eau et calcule le SHA-256 en même temps.

Côté destinataire, à chaque TAKE :

- **SEQ = attendu** → écrire, envoyer NICE, attendre SEQ + 1
- **SEQ < attendu** → doublon : ne pas réécrire, renvoyer NICE
- **SEQ > attendu** ou TOTAL différent → ignorer
- DATA > 1 024 o ou transfert inconnu → ignorer

### Doublons

| Reçu en double | Réaction |
|---|---|
| NEW même REQUEST_ID | renvoyer le même JOIN |
| READY même TRANSFER_ID | renvoyer la réponse connue |
| TAKE même SEQ | renvoyer NICE, sans réécrire |
| NICE déjà traité | ignorer |
| FINISH après la fin | renvoyer COMPLETE |
| CANCEL déjà appliqué | rester annulé |

### États d'un transfert

```
REQUESTED → WAITING_ACCEPT → SENDING ⇄ WAITING_ACK → VERIFYING → COMPLETED
À tout moment : CANCELLED (CANCEL valide) · FAILED (ERROR, timeout ou départ d'un participant)
```

| État | Déclencheur | Action |
|---|---|---|
| REQUESTED | READY reçu par l'hôte | vérifier SRC, DST, TRANSFER_ID ; enregistrer le routage |
| WAITING_ACCEPT | READY relayé | attendre YES_READY ou ERROR |
| SENDING | YES_READY reçu | envoyer le bloc suivant |
| WAITING_ACK | TAKE envoyé | garder le bloc, attendre son NICE |
| VERIFYING | FINISH reçu | vérifier taille et SHA-256 |
| COMPLETED | empreinte correcte | envoyer COMPLETE, libérer les ressources |

Le destinataire abandonne aussi un transfert s'il ne reçoit plus rien pendant **15 s** (ERROR `TRANSFER_TIMEOUT`).

## 10. Retransmissions et délais

Si la réponse attendue n'arrive pas à temps, l'émetteur **renvoie le même message**, avec les mêmes identifiants, en doublant le délai.

```
envoi   essai 1   essai 2    essai 3      essai 4          essai 5          échec
  ●───────●─────────●──────────●─────────────●────────────────●────────────────✕
  0 s    0,5 s     1,5 s      3,5 s         7,5 s           11,5 s          15,5 s
     +0,5 s    +1 s      +2 s          +4 s            +4 s            +4 s
```

| Paramètre | Valeur v1 |
|---|---|
| Délai initial (RTO) | **500 ms** |
| Progression | × 2 à chaque essai |
| Plafond | 4 s |
| Essais max. | 5 (après l'envoi initial) |
| AVAILABLE / SYNCTABLE | toutes les 5 s |
| Expiration | 15 s de silence |

| Message envoyé | Réponse attendue |
|---|---|
| NEW | JOIN |
| READY | YES_READY ou ERROR |
| TAKE | NICE du même SEQ |
| FINISH | COMPLETE ou ERROR |
| CANCEL, EXIT | aucune · envoyé 3 fois |
| AVAILABLE, BYE | aucune |

### Perte d'un NICE

```
ÉMETTEUR A               HÔTE                  DESTINATAIRE B
   |──── TAKE 7 ────────▶|──── TAKE 7 ────────▶|  écrit le bloc 7
   |        ✕ NICE 7 perdu ─|◀─── NICE 7 ───────|
   |  500 ms sans NICE   |                     |
   |──── TAKE 7 ────────▶|──── TAKE 7 ────────▶|  doublon : ne réécrit pas
   |◀─── NICE 7 ─────────|◀─── NICE 7 ─────────|
   |  libère le bloc, passe au 8               |
```

> **Rôle de l'hôte** — il relaie TAKE et NICE sans les confirmer lui-même. Un TAKE reçu par l'hôte n'est pas un TAKE reçu par le destinataire : seul le NICE du destinataire libère le bloc.

## 11. Erreurs et interruptions

| Code | Nom | Envoyé par | Quand |
|---|---|---|---|
| `0x0001` | INVALID_MESSAGE | Tout participant | Structure incohérente (si une réponse est sûre) |
| `0x0002` | UNKNOWN_PARTICIPANT | Hôte | DST absent ou endpoint inconnu |
| `0x0003` | UNKNOWN_TRANSFER | Hôte ou destinataire | TRANSFER_ID inconnu ou expiré |
| `0x0004` | TRANSFER_REJECTED | Destinataire | Refus du fichier (au lieu de YES_READY) |
| `0x0005` | HASH_MISMATCH | Destinataire | SHA-256 différent de celui annoncé |
| `0x0006` | TRANSFER_TIMEOUT | Celui qui abandonne | Essais épuisés ou 15 s de silence |
| `0x0007` | TRANSFER_FAILED | Hôte ou participant | Échec générique, départ d'une des parties |
| `0x0008` | INVALID_SEQUENCE | Destinataire | Numéro de bloc incohérent (optionnel) |
| `0x0009` | SESSION_FULL | Hôte | NEW reçu alors que 8 participants sont présents |

Le registre est extensible ; un code attribué n'est jamais réutilisé. Un code inconnu est traité comme une erreur générique, sans crash.

- **CANCEL dans le désordre** : UDP peut livrer un TAKE après le CANCEL. Le destinataire garde l'état « annulé » pour ce TRANSFER_ID et ignore les blocs tardifs.
- **Un participant disparaît** : ses transferts sont interrompus, l'hôte libère le routage et prévient l'autre partie (`TRANSFER_FAILED`). Le fichier partiel est supprimé.
- **L'hôte ferme ou disparaît** : EXIT (×3) ou 15 s sans SYNCTABLE ; les clients arrêtent tout et reviennent à l'accueil.
- **Paquet malformé** : ignoré. On peut répondre INVALID_MESSAGE si c'est sûr, mais **jamais** à un ERROR, pour éviter une boucle d'erreurs.

## 12. Robustesse et sécurité

Contrôles obligatoires :

- taille minimale avant toute lecture ;
- MAGIC, VERSION, TYPE, FLAGS ;
- taille réelle = 8 + LENGTH ;
- longueurs de chaînes avant décodage UTF-8 ;
- SRC déclaré = endpoint observé ;
- DST existant et connecté ;
- seul SRC peut envoyer CANCEL ;
- jamais d'allocation fondée sur une longueur non vérifiée.

> **Limites assumées de la v1** — pas de chiffrement ni d'authentification. Un appareil connecté au même hotspot pourrait lire ou injecter des datagrammes. SHA-256 détecte une altération accidentelle, mais ne prouve pas qui a envoyé le fichier. Une version sécurisée devra le spécifier explicitement — jamais via les FLAGS.

## 13. Décisions v1 et versionnage

| Sujet | Décision v1 |
|---|---|
| MAGIC | « HRP » · `48 52 50` · en-tête de 8 octets |
| VERSION · FLAGS | `01` · `00` obligatoire, sinon paquet ignoré |
| Port | 50000/UDP sur l'hôte |
| JOIN | contient l'écho du REQUEST_ID |
| Connexion | NEW → JOIN → SYNCTABLE (AVAILABLE n'en fait plus partie) |
| Signes de vie | AVAILABLE et SYNCTABLE toutes les 5 s · expiration 15 s |
| Perte de l'hôte | détectée par 15 s sans datagramme de l'hôte |
| Retransmissions | 500 ms, × 2, plafond 4 s, 5 essais |
| TRANSFER_ID | (ID émetteur × 2^24) + compteur, compteur dès 1 ; jamais 0 |
| ERROR | 6 octets fixes ; TRANSFER_ID = 0 hors transfert |
| CANCEL · EXIT | sans accusé, envoyés 3 fois ; fichiers partiels supprimés |
| Capacité | 8 participants ; IDs non réutilisés dans une session |
| Limites | nom de participant ≤ 32 o · nom de fichier ≤ 255 o |
| Fichier vide | TOTAL = 0, FINISH directement |
| Nouvelle erreur | `0x0009` SESSION_FULL |

### Versionnage

```
v1.0  spécification figée (12 nov. 2026)
  → v1.1  ajustements après tests (délais, limites)
    → v2  évolutions : fenêtre glissante, découverte, sécurité
```

Une correction qui ne change pas le format des paquets → v1.x. Un changement de format → VERSION passe à `02`, et un appareil v1 ignore les paquets v2.

**Hors périmètre de la v1 :** Internet, cloud ou relais externe · iOS, web, ordinateur · chiffrement et authentification · reprise d'un transfert après fermeture de l'application · fenêtre glissante · découverte automatique de l'hôte.

---

## Annexe A · Structures condensées

| Code | Message | Payload | Min. |
|---|---|---|---|
| 01 | NEW | `REQUEST_ID[8] · NAMELENGTH[1] · NAME[N]` | 9 o |
| 02 | JOIN | `ID[1] · HOST_NAME_LENGTH[1] · HOST_NAME[N] · REQUEST_ID[8]` | 10 o |
| 03 | SYNCTABLE | `HOSTS[1] · (ID[1] · NAMELENGTH[1] · NAME[N]) × HOSTS` | 1 o |
| 04 | AVAILABLE | vide | 0 |
| 05 | BYE | vide | 0 |
| 06 | READY | `SRC[1] · DST[1] · TRANSFER_ID[4] · FILE_NAME_LENGTH[2] · FILE_NAME[N] · FILE_SIZE[8]` | 16 o |
| 07 | YES_READY | `TRANSFER_ID[4]` | 4 o |
| 08 | TAKE | `TRANSFER_ID[4] · SEQ[4] · TOTAL[4] · DATA[0..1024]` | 12 o |
| 09 | NICE | `TRANSFER_ID[4] · SEQ[4]` | 8 o |
| 0A | FINISH | `TRANSFER_ID[4] · SHA256[32]` | 36 o |
| 0B | COMPLETE | `TRANSFER_ID[4]` | 4 o |
| 0C | ERROR | `ERROR_CODE[2] · TRANSFER_ID[4]` | 6 o |
| 0D | CANCEL | `TRANSFER_ID[4]` | 4 o |
| 0E | EXIT | vide | 0 |

N = longueur indiquée par le champ de longueur qui précède.

## Annexe B · Guide d'implémentation Java

- Une classe dédiée pour construire et lire l'en-tête.
- `ByteBuffer` en `BIG_ENDIAN` explicite.
- Constantes ou `enum` pour TYPE et ERROR_CODE.
- Vérifier la longueur avant chaque `get()`.
- Séparer le parseur réseau de la logique Android.
- Générer REQUEST_ID une fois par tentative (`SecureRandom`).
- SHA-256 incrémental avec `MessageDigest`.
- Jamais d'UDP sur le thread UI : un thread dédié.
- Un état explicite par transfert.
- Journaliser TYPE, TRANSFER_ID, SEQ et l'heure — pas le contenu des fichiers.

```java
static final byte[] MAGIC = { 0x48, 0x52, 0x50 }; // "HRP"
static final int HEADER_SIZE = 8, PORT = 50000, MAX_DATA = 1024;

int transferId(int senderId, int counter) {
    return (senderId << 24) | (counter & 0xFFFFFF);
}
```

## Annexe C · Checklist de tests

- [ ] NEW valide, NEW dupliqué (même REQUEST_ID), NEW d'une autre tentative
- [ ] Session pleine → SESSION_FULL
- [ ] SYNCTABLE avec plusieurs noms accentués
- [ ] AVAILABLE, expiration à 15 s, BYE
- [ ] Disparition brutale de l'hôte détectée en 15 s
- [ ] READY accepté, refusé, dupliqué, destinataire inconnu
- [ ] TAKE de 1 o, 1 024 o, moins de 1 024 o
- [ ] Perte de TAKE, perte de NICE, doublons
- [ ] FINISH avec empreinte correcte et incorrecte
- [ ] Fichier vide, fichier multiple exact de 1 024
- [ ] CANCEL par l'émetteur, par un autre, TAKE tardif
- [ ] EXIT pendant un transfert
- [ ] Datagramme tronqué, LENGTH faux, TYPE / VERSION inconnus, FLAGS ≠ 0
- [ ] Deux transferts simultanés vers des destinataires différents
