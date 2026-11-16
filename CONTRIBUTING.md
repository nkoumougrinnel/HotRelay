# Comment on travaille sur HotRelay

## Règle n° 1 : on ne pousse jamais directement sur `main`

`main` doit **toujours compiler**. Tout passe par une branche et une Pull Request relue par un autre membre.

## 1. Prendre une tâche

- Chaque tâche est une **issue** GitHub (une journée de travail maximum).
- Lire « À comprendre avant de coder » et chercher la notion **avant** d'écrire du code.
- Déplacer la carte dans le Kanban : **À faire → En cours**.
- Bloqué plus de 2 h ? Commenter l'issue et prévenir le groupe.

## 2. Créer sa branche

Toujours depuis un `main` à jour :

```bash
git switch main
git pull
git switch -c feat/<numéro>-<mot>
```

| Type | Nom de branche | Exemple |
|---|---|---|
| Nouvelle fonctionnalité | `feat/<n°>-<mot>` | `feat/10-udp-transport` |
| Correction de bug | `fix/<n°>-<mot>` | `fix/25-timeout-nice` |
| Documentation | `docs/<n°>-<mot>` | `docs/31-demo` |

Minuscules, mots séparés par des tirets, pas d'accents.

## 3. Écrire ses commits

Format : `type: ce que fait le commit` — au présent, en français, une seule idée par commit.

| Type | Quand |
|---|---|
| `feat` | nouvelle fonctionnalité |
| `fix` | correction de bug |
| `docs` | documentation |
| `test` | tests |
| `refactor` | réorganisation sans changer le comportement |
| `chore` | configuration, Gradle, fichiers divers |

```
feat: ajoute la boucle de réception UDP
fix: ignore les paquets TAKE dont le SEQ dépasse TOTAL
docs: décrit le scénario de démo
```

À **ne jamais** commiter : le keystore de signature (`*.jks`, `*.keystore`), `local.properties`, les dossiers `build/`, les mots de passe.

## 4. Ouvrir une Pull Request

```bash
git push -u origin feat/<numéro>-<mot>
```

Puis sur GitHub, ouvrir une PR vers `main` :

- **Titre** : ce que fait la PR.
- **Description** : ce qui change, comment le tester, et `Closes #<numéro>` (l'issue se fermera toute seule à la fusion).
- Demander la relecture d'**un autre membre**.
- Kanban : **En cours → En revue**.

## 5. Relire une Pull Request

Le relecteur :

1. Récupère la branche et vérifie que **ça compile** (`Build > Make Project`).
2. Lit le code : est-il compréhensible ? respecte-t-il [`docs/protocole.md`](docs/protocole.md) ?
3. Commente, ou approuve.

L'auteur fusionne une fois la PR approuvée, puis supprime sa branche.

## 6. Quand une tâche est-elle finie ?

**Seulement** quand :

- le critère « ✅ Terminé quand » de l'issue est vérifié **sur un vrai téléphone** ;
- la PR est fusionnée dans `main` ;
- on a expliqué son code aux deux autres en 5 minutes. Si on ne peut pas l'expliquer, ce n'est pas fini.

Une issue fermée sans que son critère soit rempli doit être **rouverte**.

## Conventions de code

- **Le protocole fait foi** : noms des messages (NEW, JOIN, SYNCTABLE, TAKE, NICE…) et valeurs exactement comme dans [`docs/protocole.md`](docs/protocole.md). Les constantes sont dans `protocol/Hrp.java`, nulle part ailleurs.
- **Jamais de réseau sur le thread UI** (`NetworkOnMainThreadException`) : passer par `network/UdpTransport`.
- Un paquet reçu est une entrée non fiable : on vérifie les longueurs, on ignore ce qui est invalide, **on ne plante jamais**.
- Séparer les rôles : l'interface (`ui/`) n'ouvre pas de socket, le réseau (`network/`) ne touche pas aux vues.
- Logs : un `TAG` par classe, on journalise TYPE, TRANSFER_ID et SEQ, jamais le contenu des fichiers.
- Noms de classes en anglais (`HostSession`), commentaires en français.
