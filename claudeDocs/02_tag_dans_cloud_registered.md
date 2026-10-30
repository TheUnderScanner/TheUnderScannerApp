# Underscanner — Point 2 : récupérer le `tag` (et `line`) Livox jusqu'au nuage affiché

**Question de départ :** « Mon mode couleur *Tag* et mon *filtre bruit* s'appuient sur l'octet
`tag` de Livox, mais en pratique il arrive souvent à `0`. Comment savoir exactement ce que
`/cloud_registered` transporte, et à quel niveau agir pour que le `tag` survive jusqu'à l'app ? »

Réponse courte : le `tag` existe côté driver, mais **FAST-LIO le jette** en fabriquant ses points
finaux. Donc soit on le republie depuis le flux brut (rapide, côté backend), soit on modifie
FAST-LIO (plus propre, et seul moyen de le garder aussi dans le `.pcd` sauvegardé).

---

## 1. Vérifier exactement ce que `/cloud_registered` transporte

Un `PointCloud2` décrit ses canaux dans son champ `fields` (chaque `PointField` = `name`,
`offset`, `datatype`, `count`). C'est la source de vérité.

**Le backend le logge déjà.** Dans `appV2.py`, `PreviewNode.on_cloud` écrit une fois :
```python
self.get_logger().info(f"{TOPIC_CLOUD} point fields: {sorted(present)}")
```
→ visible dans le terminal qui lance `python3 appV2.py` (ou `ros2 topic echo /rosout`).

**En CLI, pendant qu'un scan tourne** (ROS sourcé) :
```bash
ros2 topic echo /cloud_registered --field fields --once   # la liste des canaux
ros2 topic info -v /cloud_registered                       # type + éditeurs/abonnés
ros2 topic hz  /cloud_registered                           # débit
```
Dans `fields`, `datatype` est un code : **2 = uint8, 7 = float32, 8 = float64**. Un `tag` /
`line` apparaîtrait en `datatype: 2`.

**Comparer avec la source brute** (le driver, qui, lui, a le tag) :
```bash
ros2 topic type /livox/lidar                       # PointCloud2 ? ou livox_ros_driver2/msg/CustomMsg ?
ros2 interface show livox_ros_driver2/msg/CustomMsg
ros2 topic echo /livox/lidar --field fields --once # si c'est un PointCloud2
```

Résultat typique aujourd'hui : `/cloud_registered` = `['intensity','x','y','z']` (pas de tag),
alors que `/livox/lidar` porte `reflectivity, tag, line` par point.

---

## 2. Où le `tag` se perd

```
livox_ros_driver2   →   /livox/lidar              →   FAST-LIO2   →   /cloud_registered
(x,y,z,refl,             (PointXYZRTLT ou               (SLAM)          (carte monde)
 tag, line, time)         CustomMsg : tag, line ✓)                       x,y,z,intensity ✗ tag
```

- **`/livox/lidar` a presque sûrement le tag et la line** — c'est le format natif Livox
  (`CustomMsg` avec `reflectivity/tag/line` par point, ou le `PointCloud2` PointXYZRTLT).
- **FAST-LIO le jette.** Son type de point interne est `pcl::PointXYZINormal`
  (x, y, z, intensity + normals/curvature pour le timestamp) : **aucun champ tag/line**. Quand il
  republie `/cloud_registered` depuis ses points, seuls x,y,z,intensity survivent.

Le `tag` meurt **dans FAST-LIO**, pas dans le driver.

---

## 3. Les trois niveaux d'action

| Niveau | Ce qu'on fait | Effort | Le `.pcd` sauvegardé en profite ? |
|---|---|---|---|
| **Driver** (`livox_ros_driver2`) | Vérifier `xfer_format` (0 = PointXYZRTLT tag ✓, 1 = CustomMsg tag ✓, 2 = PointXYZI tag ✗). En général déjà bon. | nul | — (le tag est déjà là, il casse en aval) |
| **Backend** (`appV2.py`) | S'abonner à `/livox/lidar` (a le tag) au lieu de `/cloud_registered`, transformer chaque frame en repère monde avec la pose `/Odometry` déjà reçue, packer tag/line. | moyen | ❌ non (le `.pcd` vient toujours du `map_save` de FAST-LIO) |
| **FAST-LIO** (fork C++) | Étendre le type de point pour porter tag/line, le remplir au préprocessing, le recopier à la publication. | élevé | ✅ **oui** |

---

## 4. Décision retenue : modifier FAST-LIO

**Pourquoi c'est le plus propre :** c'est lui qui fabrique les points finaux (le nuage recalé *et*
le `.pcd` sauvegardé via `map_save`). Corriger à la source = le `tag` devient un vrai canal de
premier ordre, disponible **en live ET hors-ligne**, sans distorsion de mouvement (FAST-LIO
dé-distord déjà avec l'IMU). C'est le seul niveau qui persiste le `tag` dans le scan enregistré.

**Ce qu'il faut toucher (repo `FAST_LIO_ROS2`) :**
1. **Le type de point** (souvent `typedef pcl::PointXYZINormal PointType;` dans `common_lib.h`) :
   définir un point custom qui ajoute `tag` et `line` (uint8), enregistré via
   `POINT_CLOUD_REGISTER_POINT_STRUCT`.
2. **`preprocess.cpp`** : au moment de lire le `CustomMsg` / PointXYZRTLT, recopier
   `points[i].tag` et `.line` dans le point (l'intensité = `reflectivity` y est déjà mise).
3. **`laserMapping.cpp` → `publish_frame_world()`** : le nuage publié est bâti à partir du type de
   point interne ; si celui-ci porte tag/line, ils ressortent tout seuls dans `/cloud_registered`.

**Les pièges à connaître avant de se lancer :**
- **`PointType` est utilisé partout** dans FAST-LIO (KD-tree ikd-Tree, undistort, downsample…).
  Changer le type touche beaucoup de fichiers ; à faire méthodiquement.
- **Le downsample voxel** (`pcl::VoxelGrid<PointType>`) **moyenne** les champs. Un `tag`
  *catégoriel* (bit-packé) ne se moyenne pas : il faut soit exclure ces champs du filtre, soit
  prendre le tag d'un représentant du voxel, soit filtrer par tag **avant** le downsample. À
  décider explicitement, sinon le tag ressort corrompu.
- Vérifier que `point_step` / la sérialisation `PointCloud2` reflète bien les nouveaux champs
  (sinon l'app lit des offsets faux).

---

## 5. Alternative gardée sous le coude (backend)

Si le fork FAST-LIO traîne, la variante **backend** donne le `tag` en live sans rien forker :
souscrire `/livox/lidar` + `/Odometry`, transformer par la pose, packer tag/line dans le format
`USC1`. Compromis : nuage brut (petite distorsion intra-frame), et le `.pcd` sauvegardé reste
sans tag. Le filtre bruit par tag est de toute façon pertinent sur le flux **brut** (Livox le
recommande avant le préprocessing).

---

## TL;DR

- `fields` de `/cloud_registered` = source de vérité ; `ros2 topic echo /cloud_registered --field fields --once` (ou le log du backend). Aujourd'hui : pas de `tag`.
- Le `tag` existe côté `/livox/lidar` mais **FAST-LIO le supprime** (son `PointType` ne le porte pas).
- **Choix propre : forker FAST-LIO** — étendre le `PointType` (+tag/line), le remplir en préproc, le republier. Seul moyen d'avoir le tag aussi dans le `.pcd`.
- **Piège n°1** : le `PointType` est partout. **Piège n°2** : le VoxelGrid moyenne les champs → gérer le tag catégoriel (filtrer avant, ou représentant par voxel).
- Repli rapide : republier `/livox/lidar` transformé côté backend (live seulement, sans tag dans le `.pcd`).
