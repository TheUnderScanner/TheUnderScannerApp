# Serveur backend qui permet la communication avec l'app Android



# Connexion SSH au Jetson

Le Jetson crée **son propre point d'accès WiFi** au démarrage ; le téléphone (et le PC) s'y
connectent. Le Jetson en est la passerelle, donc son adresse est fixe : **`10.42.0.1`**
(c'est aussi l'adresse par défaut dans les Réglages de l'app). Auth par mot de passe, pas
de clé installée pour l'instant :

```
ssh orin4slam@10.42.0.1
```

Sans internet, le Jetson n'a pas de NTP : **son horloge dérive**. L'app envoie l'heure du
téléphone à chaque démarrage de scan ; le backend date le scan avec et corrige ses
horodatages (`"clock_offset_s"` dans `/status` = l'écart corrigé, 0 tant qu'aucun scan n'a
été lancé depuis le redémarrage du service).

## Mettre à jour le backend : le script

Commite d'abord `app.py`, puis, PC connecté au point d'accès du Jetson :
```powershell
.\UnderScannerAPI\deploy-backend.ps1            # déploie le app.py de HEAD
.\UnderScannerAPI\deploy-backend.ps1 -DryRun    # vérifie seulement, ne change rien
```
Il déploie **uniquement la version commitée** (HEAD), refuse si un scan tourne, et vérifie
que la version actuelle du Jetson existe dans l'historique git de `app.py` (sinon quelqu'un
l'a modifiée sur place : il s'arrête et la garde dans `UnderScannerAPI\app.jetson.py` pour
fusion). Il contrôle la syntaxe *avant* de remplacer, garde `app.py.bak`, redémarre le
service, et revient automatiquement à l'ancienne version si le service ne répond plus.

**Pour ne pas taper le mot de passe 3 fois**, installer une fois une clé SSH :
```powershell
$env:Path += ";C:\Windows\System32\OpenSSH"
ssh-keygen -t ed25519                     # Entrée partout (pas de phrase de passe) si une clé n'existe pas déjà
type $env:USERPROFILE\.ssh\id_ed25519.pub | ssh orin4slam@10.42.0.1 "mkdir -p .ssh && chmod 700 .ssh && cat >> .ssh/authorized_keys && chmod 600 .ssh/authorized_keys"
```
Le mot de passe `sudo` du redémarrage reste demandé (une fois).

## Mettre à jour le backend à la main (ce que fait le script)

Sur Windows, `ssh`/`scp` existent mais ne sont pas toujours dans le PATH :
```powershell
$env:Path += ";C:\Windows\System32\OpenSSH"
```

**Attention** : sur le Jetson, SFTP refuse les chemins absolus → toujours
`underscanner-api/app.py` (relatif au home), jamais `~/underscanner-api/...`.

1. **Vérifier qu'on n'écrase rien** : la version du Jetson doit être celle du dernier commit
   qui a été déployé (si elle diffère, quelqu'un l'a modifiée sur place → fusionner d'abord).
   ```powershell
   scp orin4slam@10.42.0.1:underscanner-api/app.py .\UnderScannerAPI\app.jetson.py
   git hash-object .\UnderScannerAPI\app.jetson.py
   git rev-parse <commit-deployé>:UnderScannerAPI/app.py     # les deux doivent être égaux
   ```
2. **Sauvegarder, envoyer, vérifier la syntaxe**
   ```powershell
   ssh orin4slam@10.42.0.1 "cp underscanner-api/app.py underscanner-api/app.py.bak"
   scp .\UnderScannerAPI\app.py orin4slam@10.42.0.1:underscanner-api/app.py
   ssh orin4slam@10.42.0.1 "cd underscanner-api && python3 -m py_compile app.py && echo SYNTAXE_OK"
   ```
3. **Redémarrer** (aucun scan ne doit tourner : le restart le coupe)
   ```powershell
   ssh -t orin4slam@10.42.0.1 "sudo systemctl restart underscanner"
   ssh orin4slam@10.42.0.1 "journalctl -u underscanner -n 30 --no-pager"   # → Application startup complete
   curl.exe -s http://10.42.0.1:8000/status
   ```
4. **Retour arrière** si ça ne démarre pas
   ```powershell
   ssh -t orin4slam@10.42.0.1 "cp underscanner-api/app.py.bak underscanner-api/app.py && sudo systemctl restart underscanner"
   ```
5. Supprimer `UnderScannerAPI\app.jetson.py`.

Le `DeprecationWarning: on_event is deprecated` au démarrage est connu et sans effet.

## Commandes courantes une fois connecté

```
systemctl status underscanner            # le service tourne-t-il ?
sudo systemctl restart underscanner      # recharger après une modif de app.py
journalctl -u underscanner -f            # suivre les logs en direct
python3 -m py_compile app.py             # vérifier la syntaxe AVANT de redémarrer
cp app.py app.py.bak                     # filet de sécurité avant une grosse modif
ls /data/underscanner/{bags,maps,configs,notes,trajectories}   # les artefacts des scans
```

Test rapide de l'API sans passer par le téléphone (depuis le PC connecté au point d'accès) :

```
curl.exe http://10.42.0.1:8000/status
curl.exe http://10.42.0.1:8000/scans
```



# Après MAJ du fichier app.py
## Redémarrer le service pour qu'il recharge le fichier.
```
sudo systemctl restart underscanner
```

## Vérifie
```
journalctl -u underscanner -f
```

Tu dois revoir :
```
Application startup complete. 
Uvicorn running on http://0.0.0.0:8000 
```

`Ctrl+C` pour sortir du suivi des logs.






# Deux précisions utiles :
À distinguer de daemon-reload. Il y a deux commandes qui se ressemblent mais servent à des choses différentes :

`sudo systemctl restart underscanner` → tu as modifié le code (app.py). C'est ton cas courant. 
`sudo systemctl daemon-reload` → tu as modifié le fichier .service lui-même (le unit systemd). 
Rare — seulement si tu touches à /etc/systemd/system/underscanner.service.



Astuce pour le développement. Si tu es en train d'itérer beaucoup sur app.py (plusieurs modifs d'affilée), le cycle "édite → restart → teste" devient lourd. Dans ce cas, il est souvent plus pratique de couper temporairement le service et de lancer le backend à la main dans un terminal :

sudo systemctl stop underscanner

puis, dans un shell sourcé ROS :
cd ~/underscanner-api && source venv/bin/activate && python3 app.py

Comme ça tu vois les logs en direct, tu Ctrl+C / relances instantanément, et pas besoin de journalctl. Quand tu as fini d'itérer, tu remets le service :
sudo systemctl start underscanner
