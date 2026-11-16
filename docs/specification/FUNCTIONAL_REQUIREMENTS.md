# Exigences Fonctionnelles - HotRelay

## 1. Gestion des Sessions
- **Rôle Hôte :**
    - Capacité de créer une session.
    - Gestion de la liste des participants connectés.
    - Diffusion des mises à jour de la session.
    - Possibilité d'arrêter la session.
- **Rôle Client :**
    - Capacité de rejoindre une session (via saisie IP ou découverte).
    - Consultation de l'état de connexion et du rôle.
    - Possibilité de quitter la session.

## 2. Transfert de Fichiers
- **Envoi :**
    - Sélection d'un destinataire parmi les participants.
    - Sélection d'un fichier via le Storage Access Framework.
    - Transmission des données vers l'hôte.
- **Relais (Hôte) :**
    - Réception des blocs de données.
    - Routage vers le destinataire final.
- **Réception :**
    - Réception des blocs et reconstitution du fichier.
    - Notification de progression et de succès/échec.

## 3. Fiabilité et Historique
- **Protocole UDP :**
    - Implémentation d'accusés de réception (ACK).
    - Gestion des retransmissions en cas de perte de paquets.
    - Vérification d'intégrité du fichier reçu.
- **Historique :**
    - Journal local des transferts (nom, taille, sens, date, état).
