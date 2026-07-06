# Prompt Claude Code — Underscanner Android : exploiter tous les canaux du nuage

## Contexte

Le backend Jetson (`app.py`, FastAPI) a changé le **format des frames binaires** envoyées sur le WebSocket `/ws/preview`. Chaque point transporte désormais, en plus de `x,y,z`, trois canaux supplémentaires : **réflectivité, tag Livox, et numéro de ligne laser**. Les messages de pose (JSON texte) sont **inchangés**.

Ton travail : adapter l'app Android (parsing WebSocket + renderer OpenGL + UI) pour **parser le nouveau format** et **exploiter ces canaux** via des modes de coloration et un filtre. L'ancien format n'est plus émis : il faut migrer le parsing, pas maintenir la compatibilité.

Commence par explorer le repo pour localiser : le client WebSocket qui reçoit les frames binaires, le renderer OpenGL (le viewer réutilisé entre la Scan Library et l'Active scan screen), et l'écran de scan actif (HUD). Le renderer fait déjà un **voxel-dedup côté client + un point cap** — il faut le préserver en propageant les nouveaux attributs.

---

## 1. Nouveau format de frame binaire (`USC1`)

Chaque frame binaire WebSocket est un blob unique, **little-endian** :

```
Header (8 octets) :
    char[4]  magic        = "USC1"        (ASCII ; sert aussi de tag de version)
    uint32   point_count

Puis point_count enregistrements de 16 octets chacun, entrelacés :
    offset  0 : float32  x
    offset  4 : float32  y
    offset  8 : float32  z
    offset 12 : uint8    reflectivity   (0-255)
    offset 13 : uint8    tag            (octet tag Livox ; 0 si indisponible)
    offset 14 : uint8    line           (id laser ; 0 si indisponible)
    offset 15 : uint8    _reserved      (0)
```

Détails importants :
- **Valider le magic** `"USC1"` en tête ; si absent, ignorer la frame (log warning). Ne pas planter.
- Le stride de **16 octets est aligné sur 4** : les 3 coords sont 3×float32 à l'offset 0, et les 4 uint8 finaux peuvent être lus comme un attribut `vec4` d'octets **normalisés** (`GL_UNSIGNED_BYTE`, `normalized=true`) dans le VBO entrelacé. Utilise ça pour éviter toute copie/déinterleave.
- Le backend expose `GET /preview/format` qui renvoie ce layout en JSON — utile pour un test d'intégration, mais le format ci-dessus fait foi.

Réécris le parsing en conséquence : lire l'en-tête, vérifier le magic, lire `point_count`, puis charger directement le bloc d'octets dans un buffer natif (`ByteBuffer` direct, `order(LITTLE_ENDIAN)`) que le VBO consomme avec le bon stride/offsets.

---

## 2. Modes de coloration (nouveau sélecteur dans le viewer)

Ajouter un sélecteur de **mode de couleur** dans le viewer (menu ou boutons dans l'Active scan screen, et idéalement aussi dans le viewer de la Library). Ce selecteur est rangé à gauche du bouton d'option qui est tout en bas à droite. Ce selecteur doit agir comme ce btn option. On clique sur le btn selecteur, il fait apparaitre tous les modes en tant que colonne de btn , la colonne de btn ne se cache pas automatiquement, l'user doit la fermer volonatairement. Si il existe une option on/off à un mode alors un btn toggle on/off (encore un peu plus petit) pour cette option de mode à gauche du btn du mode en question. Si il existe une option à valeur nombre continu ou entiers : un slider horyzontale à gauche du btn du mode (slider dont la valeur la plus faible est la plus loin du btn : valeurs croissantes de gauche à droite le long du slider). Il peut exister des doubles slider (1 slider avec 2 bitognos). Utiliser ça pour mettre un seuil inf et sup pour le mode reflectivité.(qui colorirai tous les point en dehors avec la meme couleur max ou min )


Il est clair quel mode est actif quand la colonne de btn de mode est visible.


Modes :

1. **Uniforme** — comportement actuel (une seule couleur). Garder comme défaut ou option.
2. **Intensité (réflectivité)** — colormap sur `reflectivity` 0-255. **Mode le plus important.**
3. **Hauteur (Z)** — colormap sur la coordonnée `z` du point (min/max glissants sur le nuage visible).
4. **Distance au capteur** — colormap sur `distance = ||point - sensor||`, où `sensor` = la dernière position reçue via le message de pose JSON (`x,y,z`). Le nuage est en repère monde, la pose aussi, donc la distance se calcule côté client sans donnée backend supplémentaire.
5. **Tag** — coloration catégorielle par état du tag (voir §3). N'a d'effet visible que si le backend fournit des tags non nuls ; sinon tout apparaît « normal ». Gérer ce cas proprement (pas d'artefact).

Implémentation recommandée : passer le mode en **uniform au shader** et faire le mapping couleur dans le **fragment/vertex shader** à partir des attributs déjà présents dans le VBO (reflectivity/tag/z), pour éviter de reconstruire les buffers à chaque changement de mode. La position du capteur (mode distance) peut être passée en uniform `vec3` mise à jour à chaque pose.

### Détails colormap intensité (mode 2)
- Mapper 0-255 sur un gradient perceptuel (type **turbo** ou **viridis**). Un gradient codé en dur dans le shader (quelques stops interpolés) suffit.
- **Bonus très utile pour ce projet** : la réflectivité Livox se lit en deux bandes — `0-150` = surfaces diffuses (réflectivité réelle 0-100 %), `151-255` = réflexion quasi totale (**eau / roche mouillée / calcite brillante / métal**). Prévoir une **option** « surligner spéculaire » qui rend la bande `>150` dans une teinte distincte et saturée (p. ex. cyan/magenta vif) pour repérer d'un coup d'œil les zones humides — ce sont précisément les zones à risque pour le SLAM. Laisser cette option togglable ; par défaut, gradient continu.

En fait on va plutot l'implementer sous la forme du double slider pour borner comme on veut. Pas d'option on/off surligner spéculaire mais un double slider.

---

## 3. Filtre par tag Livox (toggle + niveau)

Le `tag` est un octet bit-packé. Décodage (convention Livox SDK ; **à vérifier** contre le driver réel car il varie selon firmware) :

- **Bruit basé sur la position spatiale** = `tag & 0x03` (bits 1-0)
- **Bruit basé sur l'intensité de retour** = `(tag >> 2) & 0x03` (bits 3-2)

Valeurs pour chacun : `0 = normal`, `1 = forte confiance que c'est du bruit`, `2 = confiance moyenne`, `3 = faible confiance (probablement réel)`.

UI : un contrôle **filtre bruit** à 3 positions :
- **Off** — tout afficher (défaut).
- **Conservateur** — masquer les points où le bruit spatial **ou** intensité vaut `1` (bruit quasi certain : poussière, filaments d'arête).
- **Agressif** — masquer aussi la valeur `2` (pluie/brouillard/buée probables).

Le filtrage doit se faire côté GPU (discard dans le fragment shader selon un uniform de seuil) **ou** en amont du remplissage du VBO — au choix, mais il doit rester compatible avec le voxel-dedup/point-cap existant et ne pas coûter un rebuild complet à chaque changement de seuil (préférer le discard shader).

⚠️ **Important** : en pratique, avec le FAST-LIO actuel, les tags arriveront probablement à `0` (le backend loggue les champs réellement disponibles au démarrage). Le filtre doit donc être **inoffensif quand tous les tags sont 0** (ne rien masquer) et l'UI ne doit pas laisser croire à un bug. Optionnel : griser le contrôle filtre/tag si aucune frame reçue ne contient de tag non nul.

---

## 4. Contraintes de perf et d'archi

- Préserver le **voxel-dedup client + point cap** existants ; ils doivent maintenant transporter les attributs `reflectivity/tag/line` avec chaque point conservé (ne pas les perdre au dedup).
- Viser un chemin **zéro-copie** : `ByteBuffer` direct → `glVertexAttribPointer` avec stride 16 et offsets 0 (position, 3×float) et 12 (attributs, 4×ubyte normalisés). Pas de parsing point-par-point en Kotlin/Java dans la hot loop.
- Le reste du pipeline live (reconnexion WS, HUD, comportement « quitter n'arrête pas le scan ») reste inchangé.
- Les messages **pose JSON** ne changent pas ; juste s'assurer que la dernière position est disponible au renderer pour le mode distance.

---

## 5. Critères d'acceptation

1. L'app parse le format `USC1`, valide le magic, et affiche le nuage live comme avant en mode Uniforme.
2. Un sélecteur permet de basculer entre Uniforme / Intensité / Hauteur / Distance / Tag sans reconstruire les buffers (changement instantané).
3. Le mode Intensité affiche un gradient lisible et l'option « surligner spéculaire » distingue nettement la bande `>150`.
4. Le mode Distance utilise la position de pose courante et se met à jour quand la pose bouge.
5. Le filtre bruit à 3 niveaux masque/affiche les points sans casser le dedup ni le point cap, et est inoffensif quand tous les tags valent 0.
6. Aucune régression sur la reconnexion WS, le HUD, ni le viewer de la Library.

Explore d'abord le repo et propose-moi le plan de modif (fichiers touchés) avant d'implémenter.
