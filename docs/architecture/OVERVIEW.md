# Vue d'Ensemble de l'Architecture - HotRelay

## 1. Modèle de Communication
Le système repose sur un modèle **Étoile** où l'hôte est le hub central.

**Flux de données :**
`Client A (Expéditeur)` $\xrightarrow{UDP}$ `Hôte (Relais)` $\xrightarrow{UDP}$ `Client B (Destinataire)`

## 2. Découpage Logiciel (Packages)
Le code est organisé en composants distincts pour séparer la logique métier de l'interface :
- **`ui`** : Fragments et Activités pour l'affichage.
- **`session`** : Gestion des rôles, état de la session et liste des participants.
- **`network`** : Gestion des sockets UDP et des flux de données.
- **`protocol`** : Définition des types de messages et sérialisation.
- **`transfer`** : Orchestration du découpage en blocs et gestion des ACK.
- **`files`** : Interaction avec le système de fichiers Android.

## 3. Stratégie de Fiabilité
Comme UDP est non fiable, HotRelay implémente :
- **Séquençage** : Chaque bloc possède un numéro d'ordre.
- **ACK** : Le destinataire confirme la réception de chaque bloc.
- **Timeout** : L'expéditeur renvoie le bloc si aucun ACK n'est reçu après un délai défini.
